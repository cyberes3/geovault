---
trigger: always_on
---

# The following are extremely relevant and important instructions. It would behoove you to follow them.

Always use top-level imports in Python, not local/function level!

The backend venv is at src/backend/venv

Run tests with src/tests/run-tests.sh. We have like 2000+ tests so do not blindly run this script. Instead, run specific tests. More instructions available at src/tests/README.md. Read this before running any tests.
YES: `./run-tests.sh test_api/test_app_releases.py` NO: `src/tests/run-tests.sh src/tests/test_api/test_app_releases.py`

Do not re-export modules or use __all__. Instead, do direct imports.

DO NOT MANUALLY CREATE OR EDIT DJANGO MIGRATIONS!!!!! Use `manage.py` to generate them. 

Prioritize using the provided Heroicons components for icons on the frontend instead of creating custom SVGs.
For example `import { ChevronDownIcon, Bars3Icon, XMarkIcon } from '@heroicons/vue/24/{outline or solid}';`

Make sure to explcitly define responses via pydantic for the backend and typescript for the frontend.

We have a bunch of common frontend components in the `parts/` folder. Please use those where possible.

We like titles like `Example Title` in this case instead of `Example title`.

Android apps should be built with `./build-android.sh` located in the root of each app src dir.
These apps share visual styles so make sure that changes are made in all apps.
Apps only target the latest Android version and SDK so do not do things like `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)`.
YOU ARE NOT ALLOWED TO DO `@Suppress("DEPRECATION")`!!!!!! YOU MUST FIX IT!!!!!!!
We are using Material Design 2 only.
We have preset color pallets in `android-common`. Please prioritize using those instead of hard-coding hex colors. 
No shadows on anything.
The "survey app" is symlinked in to `src/android-survey-data-viewer`. It may not show up correctly in your tools.
`android-common` also has a lot of common UI components that you should use.

External repos are provided for you in the `external sources/` directory. This folder is read-only. If you need to reference the source code of a library please check here before fetching GitHub. 