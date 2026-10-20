#!/usr/bin/env python3
"""Explicit, token-paired diagnostic inbox. No remote commands or directory serving."""
import argparse
import hashlib
import hmac
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_REPORT = 8_388_608
PAGE = b'''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Altiro live lab</title>
<style>
:root{color-scheme:light dark;font:17px system-ui;background:#dce2da;color:#222621}
body{max-width:42rem;margin:3rem auto;padding:0 1.2rem}h1{font-size:2rem}
button,a,input{font:inherit}button,a{color:inherit}button{min-height:48px;padding:0 1rem}
input{box-sizing:border-box;width:100%;padding:.7rem;margin:.5rem 0 1rem}
pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:.85rem}
:focus-visible{outline:3px solid #385d48;outline-offset:3px}
@media(prefers-color-scheme:dark){:root{background:#1c2420;color:#e7eee5}}
</style><h1>Altiro live lab</h1>
<p><a href="/altiro-debug.apk">Download the app</a></p>
<p>Console &rarr; Encoder lab &rarr; Live results. Paste this link and Connect.</p>
<label for="pair">Receiver link</label><input id="pair" readonly aria-label="Receiver pairing link">
<button id="copy">Copy link</button>
<p id="status" role="status">Waiting for a report.</p>
<details><summary>Upload an existing Share lab file</summary>
<input type="file" id="file" accept=".txt,.json,application/json,text/plain">
<button id="upload">Send report</button></details>
<pre id="analysis"></pre>
<script>
const token='SESSION_TOKEN', pair=document.getElementById('pair');
pair.value=location.origin+'/reports#'+token;
const status=document.getElementById('status');
document.getElementById('copy').onclick=()=>{pair.select();
 status.textContent=document.execCommand('copy')?'Link copied. Paste it in Altiro.':'Select and copy the link above.';};
async function refresh(){
 if(!/^[a-f0-9]{64}$/.test(token)){status.textContent='Open the complete pairing link.';return;}
 try{const r=await fetch('/status',{headers:{Authorization:'Bearer '+token},cache:'no-store'});
 if(!r.ok)throw Error();const data=await r.json();
 if(data.received){status.textContent=data.status+' / '+data.cases+' cases received at '+new Date(data.received*1000).toLocaleTimeString();
 document.getElementById('analysis').textContent=data.analysis;}}
 catch(e){status.textContent='Receiver unavailable. Check Tailscale.';}}
document.getElementById('upload').onclick=async()=>{
 const file=document.getElementById('file').files[0];if(!file){status.textContent='Choose a Share lab file.';return;}
 if(file.size>8388608){status.textContent='Report exceeds 8 MiB.';return;}
 status.textContent='Sending...';
 try{const r=await fetch('/reports',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:file});
 if(!r.ok)throw Error();await refresh();}catch(e){status.textContent='Not received. Check the file and connection.';}};
refresh();setInterval(refresh,3000);
</script></html>'''


def analyzer():
    path = Path(__file__).with_name('analyze-encoder-lab.py')
    spec = importlib.util.spec_from_file_location('encoder_analysis', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.render


class Inbox:
    def __init__(self, directory, token, render, apk=None):
        if not re.fullmatch('[a-f0-9]{64}', token):
            raise ValueError('Expected a 64-character pairing token')
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.token, self.render, self.apk = token, render, apk
        self.lock = threading.Lock()
        self.latest = {}

    def accept(self, raw):
        report = json.loads(raw, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        if (not isinstance(report, dict) or report.get('schema') != 1 or
                report.get('runtime') != 'transcribepsy-364ec361' or
                report.get('status') not in ('RUNNING', 'COMPLETED', 'FINISHED', 'FAILED',
                                             'CANCELLED', 'INTERRUPTED', 'FINISHED_WITH_FAILURES')):
            raise ValueError('Expected an Encoder lab export')
        rendered = self.render(report)
        digest = hashlib.sha256(raw).hexdigest()
        receipt = dict(sha256=digest, received=time.time(), status=report['status'],
                       cases=len(report['cases']), analysis=rendered)
        # Authoritative snapshot includes its own receipt; atomic replace survives interruption.
        target = self.directory / 'latest.json'
        staging = self.directory / 'latest.pending'
        try:
            with staging.open('wb') as stream:
                stream.write(b'{"receipt":' + json.dumps(receipt, allow_nan=False).encode() +
                             b',"report":' + raw + b'}')
                stream.flush()
                os.fsync(stream.fileno())
            staging.replace(target)
        finally:
            staging.unlink(missing_ok=True)
        self.latest = receipt
        print(json.dumps({k: v for k, v in receipt.items() if k != 'analysis'}), flush=True)
        return digest


def handler_for(inbox):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # No token, URL, device address or report body in access logs.

        def reply(self, status, body=b'', mime='text/plain; charset=utf-8'):
            self.send_response(status)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.send_header('X-Frame-Options', 'DENY')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.close_connection = True
            if self.command != 'HEAD':
                self.wfile.write(body)

        def authorized(self):
            if self.headers.get('Host') != '%s:%s' % self.server.server_address:
                self.reply(403, b'Use the numeric receiver address')
                return False
            expected = 'Bearer ' + inbox.token
            if not hmac.compare_digest(self.headers.get('Authorization', '').encode(), expected.encode()):
                self.reply(401, b'Pairing required')
                return False
            origin = self.headers.get('Origin')
            if origin is not None and origin != 'http://' + self.headers.get('Host', ''):
                self.reply(403, b'Cross-origin request refused')
                return False
            return True

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            if self.path == '/':
                if self.headers.get('Host') != '%s:%s' % self.server.server_address:
                    self.reply(403, b'Use the numeric receiver address')
                    return
                self.reply(200, PAGE.replace(b'SESSION_TOKEN', inbox.token.encode()), 'text/html; charset=utf-8')
            elif self.path == '/status':
                if self.authorized():
                    self.reply(200, json.dumps(inbox.latest).encode(), 'application/json')
            elif self.path in ('/altiro-debug.apk', '/altiro-0.6.5.apk') and inbox.apk and inbox.apk.is_file():
                with inbox.apk.open('rb') as stream:
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/vnd.android.package-archive')
                    self.send_header('Content-Length', str(os.fstat(stream.fileno()).st_size))
                    self.send_header('Cache-Control', 'no-store')
                    self.send_header('Content-Disposition', 'attachment; filename="altiro-debug.apk"')
                    self.end_headers()
                    if self.command != 'HEAD':
                        import shutil
                        shutil.copyfileobj(stream, self.wfile)
            else:
                self.reply(404)

        def do_POST(self):
            if self.path != '/reports':
                self.reply(404)
                return
            if not self.authorized():
                return
            if self.headers.get('Transfer-Encoding'):
                self.reply(400, b'Content-Length required')
                return
            length = self.headers.get('Content-Length', '')
            if not length.isascii() or not length.isdecimal():
                self.reply(411)
                return
            if len(length) > 8:
                self.reply(413)
                return
            length = int(length)
            if not 0 < length <= MAX_REPORT:
                self.reply(413)
                return
            if not inbox.lock.acquire(blocking=False):
                self.reply(503, b'Another snapshot is being received')
                return
            try:
                self.connection.settimeout(10)
                raw = self.rfile.read(length)
                if len(raw) != length:
                    raise ValueError('Incomplete report')
                digest = inbox.accept(raw)
                status, body = 200, (digest + '\n').encode()
            except (ValueError, KeyError, TypeError, IndexError, AttributeError, RecursionError):
                status, body = 422, b'Invalid Encoder lab report'
            except (OSError, TimeoutError):
                status, body = 503, b'Receiver could not save the report'
            finally:
                inbox.lock.release()
            self.reply(status, body)
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bind', required=True)
    parser.add_argument('--port', type=int, default=8774)
    parser.add_argument('--inbox', type=Path, required=True)
    parser.add_argument('--apk', type=Path, help='Optional single APK; no directories are served')
    args = parser.parse_args()
    address = ipaddress.ip_address(args.bind)
    if address not in ipaddress.ip_network('100.64.0.0/10') and args.bind != '127.0.0.1':
        parser.error('Bind to a numeric Tailnet address, or localhost for host tests')
    token = secrets.token_hex(32)
    inbox = Inbox(args.inbox, token, analyzer(), args.apk)
    server = ThreadingHTTPServer((args.bind, args.port), handler_for(inbox))
    print('Ready: paired Encoder lab receiver; no remote execution.', flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
