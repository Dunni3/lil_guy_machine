#!/usr/bin/env sh
# The Pydantic models for every row, from the JSON Schema the Kotlin row classes generate
# (schema/rows.schema.json). Run by `make server-schema`, and by the tests to check app/rows.py
# is current: they pass a temporary output path.
set -eu
cd "$(dirname "$0")"
datamodel-codegen \
  --input schema/rows.schema.json --input-file-type jsonschema \
  --output "${1:-app/rows.py}" \
  --output-model-type pydantic_v2.BaseModel \
  --target-python-version 3.12 \
  --use-standard-collections --use-union-operator --use-schema-description \
  --disable-timestamp \
  --custom-file-header "# Generated from schema/rows.schema.json (runlog/.../Rows.kt) by gen_models.sh. Don't edit; run \`make server-schema\`."
