"""Platform workflow trigger/dependency guards. No network, Docker or Maven execution."""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / '.github/workflows'
NAMES = {'backend', 'client-contracts', 'commitlint', 'console', 'e2e', 'nightly-l1', 'platform', 'webapp'}


def require_manual_trigger(source):
    """Check the repository's block-style trigger contract, including dormant comment blocks."""
    active = '\n'.join(line for line in source.splitlines() if not line.lstrip().startswith('#'))
    headers = list(re.finditer(r'(?m)^(?:on|"on"|\'on\'):(.*)$', active))
    if len(headers) != 1 or headers[0].group(1).strip():
        raise ValueError('Require one block-style on mapping')
    tail = active[headers[0].end():]
    block = re.split(r'\n\S', tail, maxsplit=1)[0]
    events = re.findall(r'(?m)^  ([a-z_]+):[^\n]*$', block)
    if events != ['workflow_dispatch']:
        raise ValueError('Platform workflows must only allow workflow_dispatch')
    # Non-canonical indentation/flow syntax must fail rather than hide another event.
    for line in block.splitlines():
        if line.strip() and not re.match(r'^  workflow_dispatch:\s*$|^ {4,}\S', line):
            raise ValueError('Unsupported trigger mapping shape')


class WorkflowContractTests(unittest.TestCase):
    def test_all_eight_platform_workflows_exist_and_are_manual_only(self):
        self.assertEqual({f'{name}.yml' for name in NAMES}, {p.name for p in WORKFLOWS.glob('*.y*ml')})
        for name in NAMES:
            with self.subTest(workflow=name):
                require_manual_trigger((WORKFLOWS / f'{name}.yml').read_text())

    def test_guard_rejects_automatic_reusable_or_ambiguous_triggers(self):
        for trigger in ['push', 'pull_request', 'schedule', 'workflow_call', 'repository_dispatch']:
            with self.subTest(trigger=trigger):
                with self.assertRaises(ValueError):
                    require_manual_trigger(f'on:\n  workflow_dispatch:\n  {trigger}:\njobs:\n  x: y\n')
        for value in ['on: [workflow_dispatch, push]\n', 'on:\n  workflow_dispatch:\non:\n  push:\n',
                      'on:\n  workflow_dispatch:\n   push:\n', 'on:\n  workflow_dispatch:\n  "push":\n']:
            with self.assertRaises(ValueError):
                require_manual_trigger(value)
        require_manual_trigger('# PAUSED_TRIGGER on:\n# PAUSED_TRIGGER   push:\non:\n  workflow_dispatch:\njobs:\n  x: y\n')

    def test_backend_prepares_real_test_tls_before_unfiltered_maven(self):
        source = (WORKFLOWS / 'backend.yml').read_text()
        prepare = source.index('          bash ../scripts/prepare-test-tls.sh')
        maven = source.index('          ./mvnw -B clean verify')
        self.assertLess(prepare, maven)
        self.assertNotIn('-DskipTests', source)
        self.assertNotIn('continue-on-error:', source)
        self.assertIn('python3 -m unittest scripts.tests.test_workflow_contracts', source)
        for file in ['scripts/prepare-test-tls.sh', 'things-link/mvnw',
                     'things-link/things-link-testing/src/main/java/com/things/link/testing/tls/TestTlsMaterial.java']:
            self.assertTrue((ROOT / file).is_file(), file)

    def test_workflow_direct_script_and_package_inputs_exist_on_master(self):
        for name in NAMES:
            source = (WORKFLOWS / f'{name}.yml').read_text()
            with self.subTest(workflow=name):
                # Literal run directories and setup action package/lock inputs are root-relative.
                for key in ['working-directory', 'package_json_file', 'cache-dependency-path']:
                    for value in re.findall(rf'(?m)^\s*{key}:\s*([^\n]+)$', source):
                        if value.strip() not in ('|', '>'):
                            self.assertTrue((ROOT / value.strip().strip('\"\'')).exists(), value)
                for module in re.findall(r'python3 -m unittest (scripts\.tests\.[a-z_]+)', source):
                    self.assertTrue((ROOT / (module.replace('.', '/') + '.py')).is_file(), module)
                for script in re.findall(r'(?<![\w/])((?:scripts|things-link)/[A-Za-z0-9_./-]+\.(?:py|sh|ps1))', source):
                    self.assertTrue((ROOT / script).is_file(), script)
                self.assertNotIn('codex/open-source', source)

    def test_console_and_e2e_use_one_platform_candidate_without_independent_checkout(self):
        console = (WORKFLOWS / 'console.yml').read_text()
        e2e = (WORKFLOWS / 'e2e.yml').read_text()
        for source in (console, e2e):
            self.assertNotIn('BACKEND_REF', source)
            self.assertNotIn('THINGS_LINK_READONLY_SSH_KEY', source)
            self.assertNotIn('repository:', source)
            self.assertNotIn('integration/', source)
            self.assertIn('ref: ${{ github.sha }}', source)
            self.assertIn("if: github.ref == 'refs/heads/master'", source)
            self.assertIn('git -C things-link-console rev-parse --show-toplevel', source)
        # 手动 E2E 的静态前置步骤与独立手动 console 工作流保持一致，禁止删减。
        self.assertEqual(console.split('  verify:\n', 1)[1].strip(),
                         e2e.split('  verify:\n', 1)[1].split('\n  e2e:', 1)[0].strip())
        self.assertIn('    needs: verify', e2e)
        self.assertIn('./scripts/run-e2e-tests.sh --with-simulator', e2e)
        self.assertIn('things-link-console/playwright-report/', e2e)
        self.assertFalse((ROOT / 'things-link-console/.github').exists())

    def test_node_workflow_commands_and_recursive_package_inputs_exist(self):
        commands = {'things-link-console': ['build', 'test', 'api:check', 'lint', 'lint:stylelint:check'],
                    'things-link-platform': ['build', 'test:production-config', 'verify:deployment', 'verify:accessibility', 'verify:performance'],
                    'things-link-webapp': ['verify', 'build', 'api:check', 'test'],
                    'things-link-client-contracts': ['verify', 'contract:check', 'test', 'build']}
        for directory, scripts in commands.items():
            package = json.loads((ROOT / directory / 'package.json').read_text())
            self.assertTrue((ROOT / directory / 'pnpm-lock.yaml').is_file())
            for command in scripts:
                with self.subTest(package=directory, command=command):
                    self.assertIn(command, package['scripts'])
                    for path in re.findall(r'(?:node|python3)\s+(scripts/[A-Za-z0-9_./-]+)', package['scripts'][command]):
                        self.assertTrue((ROOT / directory / path).is_file(), path)

    def test_webapp_java_selectors_resolve_in_current_reactor(self):
        source = (WORKFLOWS / 'webapp.yml').read_text()
        match = re.search(r'-Dtest=([A-Za-z0-9,]+)', source)
        self.assertIsNotNone(match)
        classes = {p.stem for p in (ROOT / 'things-link').glob('*/src/test/java/**/*.java')}
        for selector in match.group(1).split(','):
            self.assertIn(selector, classes)


if __name__ == '__main__':
    unittest.main()
