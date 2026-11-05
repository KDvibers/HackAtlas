"""Run the disposable N2 environment on a Linux/WSL host with Java 21 and Docker."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def prepare(state):
    state = state.resolve()
    if state.is_relative_to(ROOT):
        raise ValueError('Ephemeral secrets must be outside the repository')
    state.mkdir(mode=0o700, parents=False, exist_ok=False)
    os.chmod(state, 0o700)
    password = secrets.token_urlsafe(32)
    for name, data in (('hmac.key', secrets.token_bytes(32)),
                       ('settings.json', json.dumps({'LIFECYCLE_DB_PASSWORD': password}).encode()),
                       ('compose.env', f'LIFECYCLE_DB_PASSWORD={password}\n'.encode())):
        descriptor = os.open(state / name, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, 'wb') as output:
            output.write(data)


def load(state):
    return json.loads((state / 'settings.json').read_text(encoding='utf-8'))


def server_environment(state, inherited, *, demo=False):
    settings = load(state)
    # Avoid unrelated deployment overrides/config imports in the closed launcher.
    env = {key: value for key, value in inherited.items() if not key.startswith(
        ('SPRING_', 'SERVER_', 'HACKATLAS_', 'MANAGEMENT_', 'LOGGING_', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',
         '_JAVA_OPTIONS', 'JAVA_OPTS'))}
    env.update(SPRING_PROFILES_ACTIVE='dev,lifecycle-test', HACKATLAS_BIND_ADDRESS='127.0.0.1', PORT='8081',
               HACKATLAS_DB_URL='jdbc:postgresql://127.0.0.1:55432/hackatlas_lifecycle_test',
               HACKATLAS_DB_USERNAME='lifecycle_test_owner', HACKATLAS_DB_PASSWORD=settings['LIFECYCLE_DB_PASSWORD'],
               HACKATLAS_LIFECYCLE_KEY_FILE=str(state / 'hmac.key'), HACKATLAS_LIFECYCLE_SMTP_PORT='11025',
               HACKATLAS_REGISTRATION_ENABLED='true', SPRING_CONFIG_IMPORT='',
               HACKATLAS_DEMO_ENABLED='true' if demo else 'false')
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'up', 'serve', 'down'])
    parser.add_argument('--state-dir', type=Path, required=True)
    parser.add_argument('--jar', type=Path, default=ROOT / 'backend/target/hackatlas-backend-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--demo', action='store_true', help='Seed marked DEMO events with the real clock (serve only)')
    args = parser.parse_args()
    if args.demo and args.command != 'serve':
        parser.error('--demo is only supported by serve')
    state = args.state_dir.resolve()
    if args.command == 'prepare':
        prepare(state)
        print('Created private synthetic settings outside Git. Do not share them.')
        return
    load(state)
    if args.command == 'serve':
        jar = args.jar.resolve(strict=True)
        # WSL localhost forwarding must see an IPv4 listener, not an IPv4-mapped IPv6 socket.
        raise SystemExit(subprocess.call(['java', '-Djava.net.preferIPv4Stack=true', '-jar', str(jar)], cwd=ROOT / 'backend',
                                        env=server_environment(state, os.environ, demo=args.demo)))
    name = 'hackatlas-lifecycle-' + hashlib.sha256(str(state).encode()).hexdigest()[:12]
    command = ['docker', 'compose', '--project-name', name, '--env-file', str(state / 'compose.env'),
               '-f', str(ROOT / 'infra/lifecycle-test/compose.yaml')]
    command += ['up', '-d', '--wait'] if args.command == 'up' else ['down']
    raise SystemExit(subprocess.call(command, cwd=ROOT))


if __name__ == '__main__':
    main()
