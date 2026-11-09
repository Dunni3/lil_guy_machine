// The crayon look's programs, ported from `Crayon.kt`. three's ShaderMaterial supplies the matrices,
// `position`, `normal` and `uv`, and turns attribute/varying/texture2D/gl_FragColor into GLSL ES 3.
// It adds no colour conversion: as in the app, these write sRGB straight to the screen.

// Normals in world space against one light for everything. Hatching runs in object space, scaled by
// u_hatchScale so it spaces the same on the guy and on furniture.
export const CRAYON_VERTEX = /* glsl */ `
  attribute vec3 a_color;
  uniform float u_hatchScale;
  varying vec3 v_n;
  varying vec2 v_uv;
  varying vec3 v_p;
  varying vec3 v_color;
  void main() {
    v_n = normalize(transpose(inverse(mat3(modelMatrix))) * normal);
    v_uv = uv;
    v_p = position * u_hatchScale;
    v_color = a_color;
    gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(position, 1.0);
  }
`;

// Two tones with a wobbly terminator; pencil hatching that starts just inside the light, thickens in
// shadow and cross-hatches in the deepest part, with breaks so it reads drawn; crayon streaks; paper
// tooth, in grains of a fixed size at 1080p whatever the resolution. Worked in linear light. The colour is per
// vertex (linear); with MAP it is multiplied by a texture, and with FACE the guy's face is swapped into that texture inside u_faceRect.
export const CRAYON_FRAGMENT = /* glsl */ `
  uniform sampler2D u_map;
  uniform sampler2D u_face;
  uniform vec4 u_faceRect;
  uniform vec3 u_lightDir;
  uniform vec3 u_shadeTint;
  uniform vec3 u_ink;
  uniform vec3 u_tint;
  uniform float u_boil;
  uniform float u_grain;
  varying vec3 v_n;
  varying vec2 v_uv;
  varying vec3 v_p;
  varying vec3 v_color;
  void main() {
    vec3 base = v_color;
    #ifdef MAP
    vec3 tex = texture2D(u_map, v_uv).rgb;
    #ifdef FACE
    vec2 f = (v_uv - u_faceRect.xy) / u_faceRect.zw;
    float inside = step(0.0, f.x) * step(f.x, 1.0) * step(0.0, f.y) * step(f.y, 1.0);
    tex = mix(tex, texture2D(u_face, clamp(f, 0.0, 1.0)).rgb, inside);
    #endif
    base *= pow(tex, vec3(2.2));
    #endif
    float ndl = dot(normalize(v_n), u_lightDir);
    float boil = u_boil;
    vec3 vP = v_p;
    float wob = 0.08 * sin(vP.x * 47.0 + boil * 2.1) * sin(vP.y * 53.0 - boil * 1.3 + vP.z * 31.0);
    float lit = smoothstep(-0.03, 0.03, ndl + 0.12 + wob);
    vec3 c = mix(base * u_shadeTint, base, lit);
    float mid = 1.0 - smoothstep(-0.03, 0.03, ndl - 0.32 + wob);
    float deep = 1.0 - smoothstep(-0.03, 0.03, ndl + 0.3 + wob);
    float brk = 0.5 + 0.5 * sin(dot(vP, vec3(-0.6, 0.9, 0.4)) * 37.0 + boil);
    float h1 = smoothstep(0.3, 0.75, sin(dot(vP, vec3(1.0, 0.85, 0.35)) * 190.0 + boil * 2.0));
    float h2 = smoothstep(0.35, 0.8, sin(dot(vP, vec3(-0.9, 1.0, 0.2)) * 170.0 - boil * 1.7));
    float hatch = h1 * (0.22 * mid + 0.4 * (1.0 - lit)) + h2 * 0.45 * deep;
    c = mix(c, u_ink, clamp(hatch * (0.55 + 0.45 * brk), 0.0, 0.75));
    float streak = sin(dot(vP, vec3(0.7, -0.5, 0.5)) * 900.0 + 4.0 * sin(dot(vP, vec3(0.2, 0.9, -0.3)) * 70.0));
    float g = fract(sin(dot(floor(gl_FragCoord.xy / u_grain), vec2(12.9898, 78.233))) * 43758.5453);
    c *= 0.95 + 0.03 * streak + 0.065 * g;
    gl_FragColor = vec4(pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)) * u_tint, 1.0);
  }
`;

export const FLAT_VERTEX = /* glsl */ `
  varying vec2 v_uv;
  void main() {
    v_uv = uv;
    gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(position, 1.0);
  }
`;

export const FLAT_FRAGMENT = /* glsl */ `
  uniform vec4 u_color;
  uniform float u_useMap;
  uniform sampler2D u_texture;
  varying vec2 v_uv;
  void main() {
    vec4 t = texture2D(u_texture, v_uv);
    gl_FragColor = u_color * mix(vec4(1.0), t, u_useMap);
  }
`;

export const PAPER_VERTEX = /* glsl */ `
  attribute vec3 a_color;
  varying vec2 v_uv;
  varying vec3 v_color;
  void main() {
    v_uv = uv;
    v_color = a_color;
    gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(position, 1.0);
  }
`;

// Big surfaces: the painted texture, times the vertex colour (the side's shade, baked once), times
// the room's light. One sample and nothing per pixel, since it covers most of the screen.
export const PAPER_FRAGMENT = /* glsl */ `
  uniform sampler2D u_texture;
  uniform vec3 u_tint;
  varying vec2 v_uv;
  varying vec3 v_color;
  void main() {
    gl_FragColor = vec4(texture2D(u_texture, v_uv).rgb * v_color * u_tint, 1.0);
  }
`;
