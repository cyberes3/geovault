# Third-party attribution

`geo_lib.togeojson` is a Python port of [`@tmcw/togeojson`](https://github.com/placemark/togeojson)
v7.1.2, which is licensed under the BSD 2-Clause License:

```
Copyright (c) 2019 Tom MacWright, Mapbox All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

This package (`geo_lib/togeojson/`) does not vendor or copy `@tmcw/togeojson`'s
source code; it is an independent Python re-implementation of the same KML/GPX
-> GeoJSON conversion behavior (see the package docstring in `__init__.py` for
the exact scope). It is nonetheless a derivative work under the terms above,
so this notice is retained per condition 1 of the license.

GeoVault as a whole is licensed under the GNU Affero General Public License
v3 (see the repository's top-level `LICENSE`). The BSD 2-Clause License is
permissive and compatible with incorporation into an AGPLv3 project.
