---
trigger: always_on
---

# The following are extremely relevant and important instructions. It would behoove you to follow them.

Always use top-level imports in Python, not local/function level!

The backend venv is at src/backend/venv

Run tests with src/tests/run-tests.sh. We have like 2000+ tests so do not blindly run this script. Instead, run specific tests.

Do not re-export modules or use __all__. Instead, do direct imports

Prioritize using the provided Heroicons components for icons on the frontend instead of creating custom SVGs.
For example `import { ChevronDownIcon, Bars3Icon, XMarkIcon } from '@heroicons/vue/24/{outline or solid}';`

Make sure to explcitly define responses via pydantic for the backend and typescript for the frontend.

We have a bunch of common frontend components in the `parts/` folder. Please use those where possible.
