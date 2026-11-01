import hashlib
import http.client
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer


SPEC = importlib.util.spec_from_file_location(
    'receiver', Path(__file__).parents[1] / 'receive-encoder-lab.py')
receiver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(receiver)


class ReceiverTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='altiro-relay-test-')
        self.token = 'a' * 64
        self.inbox = receiver.Inbox(self.directory.name, self.token, receiver.analyzer())
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), receiver.handler_for(self.inbox))
        self.worker = threading.Thread(target=self.server.serve_forever)
        self.worker.start()
        self.raw = json.dumps(dict(schema=1, runtime='transcribepsy-364ec361',
                                   status='RUNNING', cases=[], planned_cases=48)).encode()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join()
        self.directory.cleanup()

    def request(self, method, path, raw=None, authorized=True, headers=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=5)
        actual = {'Authorization': 'Bearer ' + self.token} if authorized else {}
        actual.update(headers or {})
        connection.request(method, path, body=raw, headers=actual)
        response = connection.getresponse()
        status, body = response.status, response.read()
        connection.close()
        return status, body

    def test_receipt_proves_exact_saved_payload_and_analysis_is_visible(self):
        code, digest = self.request('POST', '/reports', self.raw)
        self.assertEqual(200, code)
        self.assertEqual(hashlib.sha256(self.raw).hexdigest(), digest.decode().strip())
        saved = json.loads((Path(self.directory.name) / 'latest.json').read_text())
        self.assertEqual(json.loads(self.raw), saved['report'])
        self.assertIn(self.raw, (Path(self.directory.name) / 'latest.json').read_bytes())
        self.assertEqual(digest.decode().strip(), saved['receipt']['sha256'])
        code, raw = self.request('GET', '/status')
        self.assertEqual(200, code)
        self.assertEqual(0, json.loads(raw)['cases'])
        self.assertIn('analysis', json.loads(raw))

    def test_failed_and_partial_results_are_accepted(self):
        for status in ('FINISHED_WITH_FAILURES', 'CANCELLED', 'INTERRUPTED', 'FINISHED'):
            report = json.loads(self.raw)
            report['status'] = status
            report['live_relay_enabled'] = True
            self.assertEqual(200, self.request('POST', '/reports', json.dumps(report).encode())[0])
        self.assertIn('can perturb', self.inbox.latest['analysis'])

    def test_focused_and_full_tuning_checkpoints_arrive_without_schema_loss(self):
        from test_encoder_lab_analysis import focused_case, case
        report = json.loads(self.raw)
        full = case('gpu-tuning-f32-start', 'VULKAN', 30)
        for sample in full['samples']: sample['backend'] = 'Vulkan0'
        focused = focused_case()
        focused['status'] = 'RUNNING'
        focused['kernels'] = focused['kernels'][:1]
        report['cases'] = [full, focused]
        self.assertEqual(200, self.request('POST', '/reports', json.dumps(report).encode())[0])
        saved = json.loads((Path(self.directory.name) / 'latest.json').read_text())
        self.assertEqual(30, saved['report']['cases'][0]['variant'])
        self.assertEqual(145, saved['report']['cases'][1]['kernels'][0]['encoder_frames'])
        self.assertIn('Focused GPU tuning', saved['receipt']['analysis'])
        self.assertIn('1/8 kernel shapes', saved['receipt']['analysis'])

    def test_opencl_int8_checkpoints_and_failed_partial_results_survive_upload(self):
        from test_encoder_lab_analysis import gemm_case
        report=json.loads(self.raw)
        report['planned_cases']=2
        for variant in (80,90):
            family='int8' if variant==80 else 'acl'
            complete=gemm_case(variant)
            for count,status in ((0,'RUNNING'),(1,'RUNNING'),(2,'FAILED'),(4,'FINISHED')):
                case=json.loads(json.dumps(complete))
                case['status']=status
                case[family]=case[family][:count]
                report['cases']=[case]
                raw=json.dumps(report).encode()
                code,digest=self.request('POST','/reports',raw)
                self.assertEqual(200,code)
                self.assertEqual(hashlib.sha256(raw).hexdigest(),digest.decode().strip())
                saved=json.loads((Path(self.directory.name)/'latest.json').read_text())
                self.assertEqual(count,len(saved['report']['cases'][0][family]))
                self.assertIn(f'{count}/4',saved['receipt']['analysis'])
                self.assertNotIn('Fastest completed',saved['receipt']['analysis'])
                if count:
                    self.assertIn('environment',saved['report']['cases'][0][family][0])
                    self.assertEqual(-1,saved['report']['cases'][0][family][0]['sampled_peak_pss_kib'])

    def test_failed_gpu_interval_stays_unavailable_and_invalid_route_does_not_replace_receipt(self):
        from test_encoder_lab_analysis import gemm_case
        report=json.loads(self.raw)
        case=gemm_case(80)
        case['int8'][0]['boundary_gpu_ns']=[None]*6
        report['cases']=[case]
        raw=json.dumps(report).encode()
        self.assertEqual(200,self.request('POST','/reports',raw)[0])
        saved=(Path(self.directory.name)/'latest.json').read_bytes()
        self.assertIn('timestamps unavailable',self.inbox.latest['analysis'])
        case['int8'][0]['integer_dot_enabled']=False
        self.assertEqual(422,self.request('POST','/reports',json.dumps(report).encode())[0])
        self.assertEqual(saved,(Path(self.directory.name)/'latest.json').read_bytes())

    def test_unauthenticated_and_cross_origin_requests_are_refused(self):
        for method in ('GET', 'POST'):
            path = '/reports' if method == 'POST' else '/status'
            self.assertEqual(401, self.request(method, path, self.raw if method == 'POST' else None,
                                               authorized=False)[0])
            self.assertEqual(403, self.request(method, path, self.raw if method == 'POST' else None,
                                               headers={'Origin': 'https://example.invalid'})[0])
        self.assertFalse((Path(self.directory.name) / 'latest.json').exists())

    def test_arbitrary_files_malformed_reports_and_oversized_bodies_are_refused(self):
        for raw in (b'audio', b'{}', b'[]', b'{"schema":NaN}',
                    json.dumps(dict(schema=1, runtime='transcribepsy-364ec361',
                                    status='RUNNING', cases=[{}])).encode()):
            self.assertEqual(422, self.request('POST', '/reports', raw)[0])
        self.assertEqual(413, self.request('POST', '/reports', b'',
                                           headers={'Content-Length': str(receiver.MAX_REPORT + 1)})[0])
        self.assertEqual(400, self.request('POST', '/reports', b'',
                                           headers={'Transfer-Encoding': 'chunked'})[0])

    def test_invalid_followup_preserves_last_valid_report_and_hidden_files_stay_hidden(self):
        self.assertEqual(200, self.request('POST', '/reports', self.raw)[0])
        saved = (Path(self.directory.name) / 'latest.json').read_bytes()
        self.assertEqual(422, self.request('POST', '/reports', b'{}')[0])
        self.assertEqual(saved, (Path(self.directory.name) / 'latest.json').read_bytes())
        for path in ('/latest.json', '/.git/config', '/reports/../latest.json', '/recording.wav'):
            self.assertEqual(404, self.request('GET', path)[0])

    def test_single_apk_route_never_serves_siblings(self):
        apk = Path(self.directory.name) / 'sample.apk'
        apk.write_bytes(b'verified-apk-fixture')
        self.inbox.apk = apk
        self.assertEqual((200, b'verified-apk-fixture'), self.request('GET', '/altiro-debug.apk'))
        self.assertEqual(404, self.request('GET', '/sample.apk')[0])
        # A stale version-specific URL must not silently serve a newer APK.
        self.assertEqual(404, self.request('GET', '/altiro-0.6.5.apk')[0])


if __name__ == '__main__':
    unittest.main()
