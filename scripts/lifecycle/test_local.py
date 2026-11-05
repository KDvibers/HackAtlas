import json
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest

import local


class LocalEnvironmentTests(unittest.TestCase):
    def test_prepare_creates_private_ephemeral_key_and_never_overwrites_state(self):
        with tempfile.TemporaryDirectory() as parent:
            state = Path(parent) / 'state'
            local.prepare(state)
            self.assertEqual(32, len((state / 'hmac.key').read_bytes()))
            self.assertGreaterEqual(len(local.load(state)['LIFECYCLE_DB_PASSWORD']), 32)
            with self.assertRaises(FileExistsError):
                local.prepare(state)

    def test_state_cannot_be_created_inside_repository(self):
        with self.assertRaises(ValueError):
            local.prepare(local.ROOT / 'backend/target/forbidden-lifecycle-state')

    def test_server_environment_forces_closed_profile_and_ignores_inherited_settings(self):
        with tempfile.TemporaryDirectory() as parent:
            state = Path(parent) / 'state'
            local.prepare(state)
            env = local.server_environment(state, {'SPRING_PROFILES_ACTIVE': 'prod',
                 '_JAVA_OPTIONS': '-Dserver.address=0.0.0.0',
                 'SPRING_APPLICATION_JSON': '{"hackatlas":{"lifecycle":{"enabled":false}}}',
                 'HACKATLAS_DB_URL': 'jdbc:postgresql://remote/real', 'PATH': '/usr/bin'})
            self.assertEqual('dev,lifecycle-test', env['SPRING_PROFILES_ACTIVE'])
            self.assertNotIn('SPRING_APPLICATION_JSON', set(env))
            self.assertNotIn('_JAVA_OPTIONS', set(env))
            self.assertEqual('127.0.0.1', env['HACKATLAS_BIND_ADDRESS'])
            self.assertEqual('jdbc:postgresql://127.0.0.1:55432/hackatlas_lifecycle_test', env['HACKATLAS_DB_URL'])
            self.assertEqual('false', env['HACKATLAS_DEMO_ENABLED'])

    def test_explicit_demo_enables_synthetic_seed_without_freezing_lifecycle_clock(self):
        with tempfile.TemporaryDirectory() as parent:
            state = Path(parent) / 'state'
            local.prepare(state)
            env = local.server_environment(state, {'HACKATLAS_DEMO_AS_OF': '2026-10-12T10:00:00Z',
                 'SPRING_APPLICATION_JSON': '{"hackatlas":{"demo":{"as-of":"2026-10-12T10:00:00Z"}}}'}, demo=True)
            self.assertEqual('true', env['HACKATLAS_DEMO_ENABLED'])
            self.assertNotIn('HACKATLAS_DEMO_AS_OF', set(env))
            self.assertNotIn('SPRING_APPLICATION_JSON', set(env))
            self.assertEqual('dev,lifecycle-test', env['SPRING_PROFILES_ACTIVE'])
            self.assertEqual('127.0.0.1', env['HACKATLAS_BIND_ADDRESS'])

    def test_demo_flag_is_rejected_for_non_server_commands_before_loading_state(self):
        with tempfile.TemporaryDirectory() as parent:
            for command in ('prepare', 'up', 'down'):
                result = subprocess.run([sys.executable, str(local.ROOT / 'scripts/lifecycle/local.py'), command,
                        '--state-dir', str(Path(parent) / 'unused'), '--demo'], capture_output=True, text=True)
                self.assertEqual(2, result.returncode)
                self.assertIn('--demo is only supported by serve', result.stderr)


if __name__ == '__main__':
    unittest.main()
