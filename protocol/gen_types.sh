#!/usr/bin/env sh
# The TypeScript types for the protocol, from the JSON Schema the Kotlin types generate
# (schema/protocol.schema.json). Run in a Node container by `make protocol-types`; the header
# names the schema's SHA-256, which `make test` checks against the schema (`:protocol:checkProtocolTypes`).
# An output path other than ts/protocol.ts is for checking (`make protocol-check`).
set -eu
cd "$(dirname "$0")"
out="${1:-ts/protocol.ts}"
hash=$(sha256sum schema/protocol.schema.json | cut -d' ' -f1)
version=$(node -e 'console.log(require("./schema/protocol.schema.json")["x-protocol-version"])')
npx --yes json-schema-to-typescript@15.0.4 \
  --input schema/protocol.schema.json --output "$out.tmp" \
  --unreachableDefinitions --additionalProperties false --format \
  --bannerComment "/* Generated from schema/protocol.schema.json (protocol/.../*.kt) by gen_types.sh. Don't edit; run \`make protocol-types\`.
 * schema sha256: $hash
 */"
{ cat "$out.tmp"; printf '\n/** The protocol version these types are for; send it as ?v= when joining a stream. */\nexport const PROTOCOL_VERSION = %s;\n' "$version"; } > "$out"
rm "$out.tmp"
