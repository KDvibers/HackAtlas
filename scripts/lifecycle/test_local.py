import json
from pathlib import Path
import tempfile
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


if __name__ == '__main__':
    unittest.main()
