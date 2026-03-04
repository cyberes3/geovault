---
trigger: always_on
---

# The following are extremely relevant and important instructions. It would behoove you to follow them.

Always use top-level imports in Python, not local/function level!

The backend venv is at src/backend/venv

Run tests with src/tests/run-tests.sh. We have like 2000+ tests so do not blindly run this script. Instead, run specific tests. More instructions available at src/tests/README.md. Read this before running any tests.

Do not re-export modules or use __all__. Instead, do direct imports

Prioritize using the provided Heroicons components for icons on the frontend instead of creating custom SVGs.
For example `import { ChevronDownIcon, Bars3Icon, XMarkIcon } from '@heroicons/vue/24/{outline or solid}';`

Make sure to explcitly define responses via pydantic for the backend and typescript for the frontend.

We have a bunch of common frontend components in the `parts/` folder. Please use those where possible.

Android apps should be built with `./build-android.sh` located in the root of each app src dir. These apps share visual styles so make sure that changes are made in both places.

External repos are provided for your reference in the `external sources/` directory. This folder is read-only.