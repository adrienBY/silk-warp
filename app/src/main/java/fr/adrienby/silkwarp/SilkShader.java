package fr.adrienby.silkwarp;

/** GLSL ES 3.00 sources — identical maths to the web prototype (Twist preset). */
final class SilkShader {
    private SilkShader() {}

    static final String VERT = """
        #version 300 es
        layout(location = 0) in vec2 a;
        out vec2 vS;
        void main(){ vS = a*0.5 + 0.5; gl_Position = vec4(a, 0., 1.); }
        """;

    static final String FRAG = """
        #version 300 es
        precision highp float;
        in vec2 vS;
        out vec4 o;
        uniform sampler2D uTex;
        uniform vec2 uRes, uTexSize;
        uniform float uImgA, uP, uOff, uT, uStr, uSheen;

        mat2 rot(float a){ float c = cos(a), s = sin(a); return mat2(c, s, -s, c); }

        // Lock-screen state: the photo wound around an off-centre pivot. k = 0 -> untouched.
        vec2 lockWarp(vec2 q, float k){
          vec2 C = vec2(.5*uImgA, .5);
          vec2 P = C + vec2(-.10*uImgA, .14);
          vec2 d = q - P; float r = length(d);
          float a = k*(-.30 + 1.15*exp(-r*r/.16));
          d = rot(a)*d; d /= (1. + k*.26);
          q = P + d;
          q.x += k*.03*sin(q.y*6.5 + uT*.25);
          return q;
        }

        float lum(vec2 uv){ return dot(textureLod(uTex, uv, 4.).rgb, vec3(.3333)); }

        void main(){
          vec2 s = vec2(vS.x, 1. - vS.y);
          float pc = clamp(uP, 0., 1.);
          float k  = (1. - uP)*uStr;
          float m  = sin(3.14159*pc);

          float scrA = uRes.x/uRes.y;
          float cw = min(scrA/uImgA, 1.);
          vec2 sc = (s - .5)/(1. + .07*(1. - uP) + .07*m) + .5;
          float off = mix(.5, uOff, pc);
          vec2 uv = vec2(off*(1. - cw) + sc.x*cw, sc.y);

          vec2 q = vec2(uv.x*uImgA, uv.y);
          q = lockWarp(q, k);

          vec2 fl = vec2(sin(q.y*5. + uT*1.3 + sin(q.x*3.1)), cos(q.x*4.3 - uT*1.1 + sin(q.y*2.7)));
          fl += .5*vec2(sin(q.y*11. - uT*.9 + q.x*2.), cos(q.x*9. + uT*1.7 - q.y));
          q += fl*m*.02*uStr;
          q += (1. - pc)*.004*vec2(sin(uT*.4 + q.y*3.), cos(uT*.33 + q.x*3.));

          uv = vec2(q.x/uImgA, q.y);
          vec3 col = texture(uTex, uv).rgb;

          vec2 e = 18./uTexSize;
          float hx = lum(uv + vec2(e.x, 0.)) - lum(uv - vec2(e.x, 0.));
          float hy = lum(uv + vec2(0., e.y)) - lum(uv - vec2(0., e.y));
          vec3 n = normalize(vec3(-hx*6., hy*6., 1.));
          float la = mix(-2.4, .9, pc);
          vec3 l = normalize(vec3(cos(la)*.85, sin(la)*.85, .55));
          vec3 h = normalize(l + vec3(0., 0., 1.));
          float sp = pow(max(dot(n, h), 0.), 30.);
          col += sp*(.03 + .32*m)*uSheen;

          vec3 lockCol = (col - .5)*1.12 + .44;
          col = mix(col, lockCol, (1. - pc)*.7);
          vec2 vg = s - .5; col *= 1. - (1. - pc)*.22*dot(vg, vg)*2.;

          float g = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233)))*43758.5453);
          col += (g - .5)*.02;
          o = vec4(col, 1.);
        }
        """;
}
