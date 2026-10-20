#include <android/log.h>
#include <android/asset_manager.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <android_native_app_glue.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <random>
#include <string>
#include <thread>
#include <vector>
#include <functional>

#define CGLTF_IMPLEMENTATION
#include "third_party/cgltf.h"

#define MM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "MohallaMonsoon", __VA_ARGS__)
#define MM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MohallaMonsoon", __VA_ARGS__)

namespace mm {
constexpr float PI = 3.14159265358979323846f;
constexpr int TEX_SIZE = 2048;
constexpr int RENDER_TEX_SIZE = 1024;
constexpr int TEX_COUNT = 7;

struct Vec3 {
    float x = 0, y = 0, z = 0;
    Vec3() = default;
    Vec3(float X, float Y, float Z) : x(X), y(Y), z(Z) {}
    Vec3 operator+(const Vec3& b) const { return {x+b.x, y+b.y, z+b.z}; }
    Vec3 operator-(const Vec3& b) const { return {x-b.x, y-b.y, z-b.z}; }
    Vec3 operator*(float s) const { return {x*s, y*s, z*s}; }
};
static float dot(const Vec3& a, const Vec3& b) { return a.x*b.x+a.y*b.y+a.z*b.z; }
static Vec3 cross(const Vec3& a, const Vec3& b) {
    return {a.y*b.z-a.z*b.y, a.z*b.x-a.x*b.z, a.x*b.y-a.y*b.x};
}
static float length(const Vec3& v) { return std::sqrt(dot(v,v)); }
static Vec3 normalize(const Vec3& v) {
    float l = length(v); return l > 0.00001f ? v*(1.0f/l) : Vec3(0,1,0);
}
static float clampf(float v, float lo, float hi) { return std::max(lo, std::min(hi, v)); }
static float mixf(float a, float b, float t) { return a+(b-a)*t; }

struct Mat4 {
    std::array<float,16> m{};
    static Mat4 identity() {
        Mat4 r; r.m[0]=r.m[5]=r.m[10]=r.m[15]=1.0f; return r;
    }
    static Mat4 translation(const Vec3& p) {
        Mat4 r=identity(); r.m[12]=p.x; r.m[13]=p.y; r.m[14]=p.z; return r;
    }
    static Mat4 scale(const Vec3& s) {
        Mat4 r=identity(); r.m[0]=s.x; r.m[5]=s.y; r.m[10]=s.z; return r;
    }
    static Mat4 rotateX(float a) {
        Mat4 r=identity(); const float c=std::cos(a), s=std::sin(a);
        r.m[5]=c; r.m[6]=s; r.m[9]=-s; r.m[10]=c; return r;
    }
    static Mat4 rotateY(float a) {
        Mat4 r=identity(); const float c=std::cos(a), s=std::sin(a);
        r.m[0]=c; r.m[2]=-s; r.m[8]=s; r.m[10]=c; return r;
    }
    static Mat4 rotateZ(float a) {
        Mat4 r=identity(); const float c=std::cos(a), s=std::sin(a);
        r.m[0]=c; r.m[1]=s; r.m[4]=-s; r.m[5]=c; return r;
    }
    static Mat4 perspective(float fov, float aspect, float nearZ, float farZ) {
        Mat4 r; const float f=1.0f/std::tan(fov*0.5f);
        r.m[0]=f/std::max(0.1f,aspect); r.m[5]=f;
        r.m[10]=(farZ+nearZ)/(nearZ-farZ); r.m[11]=-1.0f;
        r.m[14]=(2.0f*farZ*nearZ)/(nearZ-farZ); return r;
    }
    static Mat4 lookAt(const Vec3& eye, const Vec3& center, const Vec3& up) {
        const Vec3 f=normalize(center-eye), s=normalize(cross(f,up)), u=cross(s,f);
        Mat4 r=identity();
        r.m[0]=s.x; r.m[1]=u.x; r.m[2]=-f.x;
        r.m[4]=s.y; r.m[5]=u.y; r.m[6]=-f.y;
        r.m[8]=s.z; r.m[9]=u.z; r.m[10]=-f.z;
        r.m[12]=-dot(s,eye); r.m[13]=-dot(u,eye); r.m[14]=dot(f,eye);
        return r;
    }
};
static Mat4 operator*(const Mat4& a, const Mat4& b) {
    Mat4 r;
    for (int c=0;c<4;c++) for (int row=0;row<4;row++) {
        float sum=0;
        for (int k=0;k<4;k++) sum += a.m[k*4+row]*b.m[c*4+k];
        r.m[c*4+row]=sum;
    }
    return r;
}
static Mat4 trs(const Vec3& p, const Vec3& rotation, const Vec3& s) {
    return Mat4::translation(p)*Mat4::rotateY(rotation.y)*Mat4::rotateX(rotation.x)*
           Mat4::rotateZ(rotation.z)*Mat4::scale(s);
}

struct Vertex {
    float px,py,pz,nx,ny,nz,u,v;
};
struct Mesh {
    GLuint vbo=0, ibo=0;
    GLsizei indexCount=0;
    GLenum mode=GL_TRIANGLES;
    void upload(const std::vector<Vertex>& vertices, const std::vector<uint16_t>& indices) {
        if (vbo) glDeleteBuffers(1,&vbo);
        if (ibo) glDeleteBuffers(1,&ibo);
        glGenBuffers(1,&vbo); glBindBuffer(GL_ARRAY_BUFFER,vbo);
        glBufferData(GL_ARRAY_BUFFER,vertices.size()*sizeof(Vertex),vertices.data(),GL_STATIC_DRAW);
        glGenBuffers(1,&ibo); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices.size()*sizeof(uint16_t),indices.data(),GL_STATIC_DRAW);
        indexCount=static_cast<GLsizei>(indices.size());
    }
    void destroy() {
        if (vbo) glDeleteBuffers(1,&vbo);
        if (ibo) glDeleteBuffers(1,&ibo);
        vbo=ibo=0; indexCount=0;
    }

};

struct RiggedCharacter {
    struct SkinVertex {
        Vertex base{};
        std::array<uint16_t,4> joints{{0,0,0,0}};
        std::array<float,4> weights{{1,0,0,0}};
    };
    cgltf_data* data=nullptr;
    std::vector<uint8_t> fileBytes;
    std::vector<SkinVertex> bindVertices;
    std::vector<Vertex> animatedVertices;
    std::vector<uint16_t> indices;
    std::vector<Mat4> inverseBind;
    std::vector<Mat4> nodeWorld;
    std::vector<Vec3> translations, scales;
    std::vector<std::array<float,4>> rotations;
    Mesh mesh;
    cgltf_node* meshNode=nullptr;
    cgltf_skin* skin=nullptr;
    cgltf_animation* animation=nullptr;
    float duration=0.0f, modelScale=1.0f, minY=0.0f, centerX=0.0f, centerZ=0.0f;
    bool ready=false;

    static Mat4 fromFloatMatrix(const float* v) {
        Mat4 m; std::copy(v,v+16,m.m.begin()); return m;
    }
    static Mat4 quatMatrix(const std::array<float,4>& q) {
        const float x=q[0],y=q[1],z=q[2],w=q[3];
        Mat4 m=Mat4::identity();
        m.m[0]=1-2*(y*y+z*z); m.m[1]=2*(x*y+z*w); m.m[2]=2*(x*z-y*w);
        m.m[4]=2*(x*y-z*w); m.m[5]=1-2*(x*x+z*z); m.m[6]=2*(y*z+x*w);
        m.m[8]=2*(x*z+y*w); m.m[9]=2*(y*z-x*w); m.m[10]=1-2*(x*x+y*y);
        return m;
    }
    static Mat4 nodeLocal(const cgltf_node* node,const Vec3& t,const std::array<float,4>& q,const Vec3& s) {
        if(node->has_matrix) return fromFloatMatrix(node->matrix);
        return Mat4::translation(t)*quatMatrix(q)*Mat4::scale(s);
    }
    static Vec3 transformPoint(const Mat4& m,const Vec3& p) {
        return {m.m[0]*p.x+m.m[4]*p.y+m.m[8]*p.z+m.m[12],
                m.m[1]*p.x+m.m[5]*p.y+m.m[9]*p.z+m.m[13],
                m.m[2]*p.x+m.m[6]*p.y+m.m[10]*p.z+m.m[14]};
    }
    static Vec3 transformNormal(const Mat4& m,const Vec3& n) {
        return normalize({m.m[0]*n.x+m.m[4]*n.y+m.m[8]*n.z,
                          m.m[1]*n.x+m.m[5]*n.y+m.m[9]*n.z,
                          m.m[2]*n.x+m.m[6]*n.y+m.m[10]*n.z});
    }
    size_t nodeIndex(const cgltf_node* n) const {
        return (n && data && n>=data->nodes && n<data->nodes+data->nodes_count)
            ? static_cast<size_t>(n-data->nodes) : data->nodes_count;
    }
    bool readVec3(const cgltf_accessor* a,cgltf_size i,Vec3& out) const {
        float v[3]={0,0,0};
        if(!a || !cgltf_accessor_read_float(a,i,v,3)) return false;
        out={v[0],v[1],v[2]}; return true;
    }
    bool load(AAssetManager* mgr,const char* path) {
        destroy();
        AAsset* a=AAssetManager_open(mgr,path,AASSET_MODE_BUFFER);
        if(!a) { MM_LOGE("Rigged GLB missing: %s; procedural character remains active",path); return false; }
        const off_t n=AAsset_getLength(a);
        if(n<=0 || n>64*1024*1024) { AAsset_close(a); return false; }
        fileBytes.resize(static_cast<size_t>(n));
        size_t offset=0;
        while(offset<fileBytes.size()) {
            const int got=AAsset_read(a,fileBytes.data()+offset,fileBytes.size()-offset);
            if(got<=0) break;
            offset+=static_cast<size_t>(got);
        }
        AAsset_close(a);
        if(offset!=fileBytes.size()) { fileBytes.clear(); return false; }
        cgltf_options options{};
        if(cgltf_parse(&options,fileBytes.data(),fileBytes.size(),&data)!=cgltf_result_success || !data) {
            MM_LOGE("Could not parse rigged GLB: %s",path); destroy(); return false;
        }
        if(cgltf_load_buffers(&options,data,nullptr)!=cgltf_result_success) {
            MM_LOGE("Could not load GLB buffers: %s",path); destroy(); return false;
        }
        for(cgltf_size i=0;i<data->nodes_count;i++) {
            if(data->nodes[i].mesh && data->nodes[i].skin) {
                meshNode=&data->nodes[i]; skin=data->nodes[i].skin; break;
            }
        }
        if(!meshNode || !skin || skin->joints_count==0 || !meshNode->mesh ||
           meshNode->mesh->primitives_count==0) {
            MM_LOGE("Rigged GLB has no skinned triangle primitive: %s",path); destroy(); return false;
        }
        const cgltf_primitive* p=&meshNode->mesh->primitives[0];
        if(p->type!=cgltf_primitive_type_triangles) { destroy(); return false; }
        const cgltf_accessor* pos=cgltf_find_accessor(p,cgltf_attribute_type_position,0);
        const cgltf_accessor* nor=cgltf_find_accessor(p,cgltf_attribute_type_normal,0);
        const cgltf_accessor* uv=cgltf_find_accessor(p,cgltf_attribute_type_texcoord,0);
        const cgltf_accessor* joints=cgltf_find_accessor(p,cgltf_attribute_type_joints,0);
        const cgltf_accessor* weights=cgltf_find_accessor(p,cgltf_attribute_type_weights,0);
        if(!pos || !joints || !weights || pos->count==0 || pos->count>65535) {
            MM_LOGE("Rigged GLB missing POSITION/JOINTS_0/WEIGHTS_0 or too many vertices");
            destroy(); return false;
        }
        bindVertices.resize(pos->count);
        animatedVertices.resize(pos->count);
        float lo[3]={1e9f,1e9f,1e9f}, hi[3]={-1e9f,-1e9f,-1e9f};
        for(cgltf_size i=0;i<pos->count;i++) {
            float v[3]={0,0,0}, nrm[3]={0,1,0}, tex[2]={0,0};
            cgltf_uint ji[4]={0,0,0,0}; float wt[4]={1,0,0,0};
            if(!cgltf_accessor_read_float(pos,i,v,3) ||
               !cgltf_accessor_read_uint(joints,i,ji,4) ||
               !cgltf_accessor_read_float(weights,i,wt,4)) { destroy(); return false; }
            if(nor) cgltf_accessor_read_float(nor,i,nrm,3);
            if(uv) cgltf_accessor_read_float(uv,i,tex,2);
            SkinVertex sv;
            sv.base={v[0],v[1],v[2],nrm[0],nrm[1],nrm[2],tex[0],tex[1]};
            float ws=0;
            for(int k=0;k<4;k++) {
                sv.joints[static_cast<size_t>(k)]=static_cast<uint16_t>(std::min<cgltf_uint>(ji[k],static_cast<cgltf_uint>(skin->joints_count-1)));
                sv.weights[static_cast<size_t>(k)]=std::max(0.0f,wt[k]); ws+=sv.weights[static_cast<size_t>(k)];
            }
            if(ws<1e-6f) { sv.weights={{1,0,0,0}}; }
            else for(float& w:sv.weights) w/=ws;
            bindVertices[i]=sv; animatedVertices[i]=sv.base;
            for(int k=0;k<3;k++) { lo[k]=std::min(lo[k],v[k]); hi[k]=std::max(hi[k],v[k]); }
        }
        if(p->indices) {
            indices.resize(p->indices->count);
            for(cgltf_size i=0;i<p->indices->count;i++) {
                const cgltf_size ix=cgltf_accessor_read_index(p->indices,i);
                if(ix>=pos->count || ix>65535) { destroy(); return false; }
                indices[i]=static_cast<uint16_t>(ix);
            }
        } else {
            indices.resize(pos->count);
            for(size_t i=0;i<indices.size();i++) indices[i]=static_cast<uint16_t>(i);
        }
        if(indices.empty() || indices.size()%3!=0) { destroy(); return false; }
        minY=lo[1]; centerX=(lo[0]+hi[0])*0.5f; centerZ=(lo[2]+hi[2])*0.5f;
        modelScale=1.8f/std::max(0.05f,hi[1]-lo[1]);
        inverseBind.resize(skin->joints_count,Mat4::identity());
        for(cgltf_size j=0;j<skin->joints_count;j++) {
            if(skin->inverse_bind_matrices) {
                float m[16];
                if(cgltf_accessor_read_float(skin->inverse_bind_matrices,j,m,16))
                    inverseBind[j]=fromFloatMatrix(m);
            }
        }
        translations.resize(data->nodes_count); scales.resize(data->nodes_count,{1,1,1});
        rotations.resize(data->nodes_count,{{0,0,0,1}});
        nodeWorld.resize(data->nodes_count,Mat4::identity());
        for(cgltf_size i=0;i<data->nodes_count;i++) {
            const cgltf_node* node=&data->nodes[i];
            if(node->has_translation) translations[i]={node->translation[0],node->translation[1],node->translation[2]};
            if(node->has_scale) scales[i]={node->scale[0],node->scale[1],node->scale[2]};
            if(node->has_rotation) rotations[i]={{node->rotation[0],node->rotation[1],node->rotation[2],node->rotation[3]}};
        }
        if(data->animations_count>0) {
            animation=&data->animations[0];
            for(cgltf_size c=0;c<animation->channels_count;c++) {
                const cgltf_accessor* input=animation->channels[c].sampler?animation->channels[c].sampler->input:nullptr;
                if(!input) continue;
                for(cgltf_size k=0;k<input->count;k++) {
                    float t=0; cgltf_accessor_read_float(input,k,&t,1); duration=std::max(duration,t);
                }
            }
        }
        mesh.upload(animatedVertices,indices);
        if(!mesh.vbo || !mesh.ibo) { destroy(); return false; }
        ready=true;
        update(0);
        MM_LOGI("Rigged GLB ready: %zu vertices, %zu joints, animation=%s",
                bindVertices.size(),skin->joints_count,animation?"yes":"no");
        return true;
    }
    void sampleChannel(const cgltf_animation_channel& ch,float time) {
        if(!ch.sampler || !ch.sampler->input || !ch.sampler->output || !ch.target_node) return;
        const cgltf_accessor* input=ch.sampler->input; const cgltf_accessor* output=ch.sampler->output;
        if(input->count==0 || output->count==0) return;
        cgltf_size a=0,b=0; float ta=0,tb=0;
        cgltf_accessor_read_float(input,0,&ta,1); tb=ta;
        if(time<=ta) b=0;
        else {
            for(cgltf_size k=1;k<input->count;k++) {
                cgltf_accessor_read_float(input,k,&tb,1);
                if(time<=tb) { a=k-1; b=k; cgltf_accessor_read_float(input,a,&ta,1); break; }
                a=k; ta=tb;
            }
            if(time>=ta && a==input->count-1) { b=a; cgltf_accessor_read_float(input,a,&ta,1); tb=ta; }
        }
        float alpha=(b==a || tb<=ta)?0.0f:clampf((time-ta)/(tb-ta),0.0f,1.0f);
        if(ch.sampler->interpolation==cgltf_interpolation_type_step) alpha=0.0f;
        const size_t ni=nodeIndex(ch.target_node); if(ni>=data->nodes_count) return;
        const size_t comps=ch.target_path==cgltf_animation_path_type_rotation?4:3;
        float va[4]={0,0,0,1}, vb[4]={0,0,0,1};
        const cgltf_size ia=(ch.sampler->interpolation==cgltf_interpolation_type_cubic_spline)?a*3+1:a;
        const cgltf_size ib=(ch.sampler->interpolation==cgltf_interpolation_type_cubic_spline)?b*3+1:b;
        if(ia>=output->count || ib>=output->count ||
           !cgltf_accessor_read_float(output,ia,va,comps) ||
           !cgltf_accessor_read_float(output,ib,vb,comps)) return;
        if(ch.target_path==cgltf_animation_path_type_translation) {
            translations[ni]={mixf(va[0],vb[0],alpha),mixf(va[1],vb[1],alpha),mixf(va[2],vb[2],alpha)};
        } else if(ch.target_path==cgltf_animation_path_type_scale) {
            scales[ni]={mixf(va[0],vb[0],alpha),mixf(va[1],vb[1],alpha),mixf(va[2],vb[2],alpha)};
        } else if(ch.target_path==cgltf_animation_path_type_rotation) {
            std::array<float,4> q{};
            float d=va[0]*vb[0]+va[1]*vb[1]+va[2]*vb[2]+va[3]*vb[3];
            if(d<0) { for(int k=0;k<4;k++) vb[k]=-vb[k]; d=-d; }
            if(d>0.9995f) {
                for(int k=0;k<4;k++) q[k]=mixf(va[k],vb[k],alpha);
            } else {
                const float th=std::acos(clampf(d,-1,1)), sn=std::sin(th);
                const float wa=std::sin((1-alpha)*th)/sn, wb=std::sin(alpha*th)/sn;
                for(int k=0;k<4;k++) q[k]=va[k]*wa+vb[k]*wb;
            }
            float qn=std::sqrt(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3]);
            if(qn>1e-6f) for(float& v:q) v/=qn;
            rotations[ni]=q;
        }
    }
    void update(float seconds) {
        if(!ready || !data || !skin) return;
        for(cgltf_size i=0;i<data->nodes_count;i++) {
            const cgltf_node* n=&data->nodes[i];
            translations[i]=n->has_translation?Vec3(n->translation[0],n->translation[1],n->translation[2]):Vec3(0,0,0);
            scales[i]=n->has_scale?Vec3(n->scale[0],n->scale[1],n->scale[2]):Vec3(1,1,1);
            rotations[i]=n->has_rotation?std::array<float,4>{{n->rotation[0],n->rotation[1],n->rotation[2],n->rotation[3]}}:std::array<float,4>{{0,0,0,1}};
        }
        if(animation && duration>1e-5f) {
            float t=std::fmod(std::max(0.0f,seconds),duration);
            for(cgltf_size c=0;c<animation->channels_count;c++) sampleChannel(animation->channels[c],t);
        }
        std::vector<uint8_t> state(data->nodes_count,0);
        std::function<void(size_t)> world=[&](size_t i) {
            if(i>=data->nodes_count || state[i]==2) return;
            if(state[i]==1) return;
            state[i]=1; const cgltf_node* n=&data->nodes[i];
            const Mat4 local=nodeLocal(n,translations[i],rotations[i],scales[i]);
            const size_t pi=nodeIndex(n->parent);
            if(pi<data->nodes_count) { world(pi); nodeWorld[i]=nodeWorld[pi]*local; }
            else nodeWorld[i]=local;
            state[i]=2;
        };
        for(size_t i=0;i<data->nodes_count;i++) world(i);
        std::vector<Mat4> jointMats(skin->joints_count,Mat4::identity());
        for(cgltf_size j=0;j<skin->joints_count;j++) {
            size_t ni=nodeIndex(skin->joints[j]);
            if(ni<data->nodes_count) jointMats[j]=nodeWorld[ni]*inverseBind[j];
        }
        for(size_t i=0;i<bindVertices.size();i++) {
            const SkinVertex& sv=bindVertices[i];
            Vec3 p{sv.base.px,sv.base.py,sv.base.pz}, n{sv.base.nx,sv.base.ny,sv.base.nz};
            Vec3 pp{0,0,0}, nn{0,0,0}; float wsum=0;
            for(int k=0;k<4;k++) {
                float w=sv.weights[static_cast<size_t>(k)]; size_t j=sv.joints[static_cast<size_t>(k)];
                if(w<=0 || j>=jointMats.size()) continue;
                pp=pp+transformPoint(jointMats[j],p)*w;
                nn=nn+transformNormal(jointMats[j],n)*w; wsum+=w;
            }
            if(wsum<1e-5f) { pp=p; nn=n; }
            animatedVertices[i]=sv.base;
            animatedVertices[i].px=pp.x; animatedVertices[i].py=pp.y; animatedVertices[i].pz=pp.z;
            Vec3 normal=normalize(nn); animatedVertices[i].nx=normal.x; animatedVertices[i].ny=normal.y; animatedVertices[i].nz=normal.z;
        }
        glBindBuffer(GL_ARRAY_BUFFER,mesh.vbo);
        glBufferSubData(GL_ARRAY_BUFFER,0,animatedVertices.size()*sizeof(Vertex),animatedVertices.data());
        glBindBuffer(GL_ARRAY_BUFFER,0);
    }
    void destroy() {
        if(mesh.vbo || mesh.ibo) mesh.destroy();
        if(data) cgltf_free(data);
        data=nullptr; fileBytes.clear(); bindVertices.clear(); animatedVertices.clear(); indices.clear();
        inverseBind.clear(); nodeWorld.clear(); translations.clear(); scales.clear(); rotations.clear();
        meshNode=nullptr; skin=nullptr; animation=nullptr; duration=0; ready=false;
    }
};
static void addFace(std::vector<Vertex>& v, std::vector<uint16_t>& ix,
                    const std::array<Vec3,4>& p, const Vec3& n) {
    const uint16_t b=static_cast<uint16_t>(v.size());
    const float uv[4][2]={{0,0},{1,0},{1,1},{0,1}};
    for (int i=0;i<4;i++) v.push_back({p[i].x,p[i].y,p[i].z,n.x,n.y,n.z,uv[i][0],uv[i][1]});
    ix.insert(ix.end(),{b,static_cast<uint16_t>(b+1),static_cast<uint16_t>(b+2),
                        b,static_cast<uint16_t>(b+2),static_cast<uint16_t>(b+3)});
}
static void makeCube(Mesh& mesh) {
    std::vector<Vertex> v; std::vector<uint16_t> ix;
    const float a=0.5f;
    addFace(v,ix,{{{-a,-a,a},{a,-a,a},{a,a,a},{-a,a,a}}},{0,0,1});
    addFace(v,ix,{{{a,-a,-a},{-a,-a,-a},{-a,a,-a},{a,a,-a}}},{0,0,-1});
    addFace(v,ix,{{{a,-a,a},{a,-a,-a},{a,a,-a},{a,a,a}}},{1,0,0});
    addFace(v,ix,{{{-a,-a,-a},{-a,-a,a},{-a,a,a},{-a,a,-a}}},{-1,0,0});
    addFace(v,ix,{{{-a,a,a},{a,a,a},{a,a,-a},{-a,a,-a}}},{0,1,0});
    addFace(v,ix,{{{-a,-a,-a},{a,-a,-a},{a,-a,a},{-a,-a,a}}},{0,-1,0});
    mesh.upload(v,ix);
}
static void makeSphere(Mesh& mesh, int rings=16, int sectors=20) {
    std::vector<Vertex> v; std::vector<uint16_t> ix;
    for (int r=0;r<=rings;r++) {
        const float vv=static_cast<float>(r)/rings, theta=vv*PI;
        const float sy=std::cos(theta), sr=std::sin(theta);
        for (int s=0;s<=sectors;s++) {
            const float uu=static_cast<float>(s)/sectors, phi=uu*2.0f*PI;
            Vec3 n(sr*std::cos(phi),sy,sr*std::sin(phi));
            v.push_back({n.x*0.5f,n.y*0.5f,n.z*0.5f,n.x,n.y,n.z,uu,vv});
        }
    }
    for (int r=0;r<rings;r++) for (int s=0;s<sectors;s++) {
        uint16_t a=static_cast<uint16_t>(r*(sectors+1)+s), b=static_cast<uint16_t>(a+sectors+1);
        ix.insert(ix.end(),{a,b,static_cast<uint16_t>(a+1),static_cast<uint16_t>(a+1),b,static_cast<uint16_t>(b+1)});
    }
    mesh.upload(v,ix);
}
static void makePlane(Mesh& mesh) {
    std::vector<Vertex> v={
        {-0.5f,0,-0.5f,0,1,0,0,0},{0.5f,0,-0.5f,0,1,0,1,0},
        {0.5f,0,0.5f,0,1,0,1,1},{-0.5f,0,0.5f,0,1,0,0,1}
    };
    std::vector<uint16_t> ix={0,2,1,0,3,2}; mesh.upload(v,ix);
}
static void makeCylinder(Mesh& mesh, int sides=14) {
    std::vector<Vertex> v; std::vector<uint16_t> ix;
    for(int y=0;y<2;y++) for(int i=0;i<=sides;i++) {
        const float a=2.0f*PI*static_cast<float>(i)/sides;
        const float x=std::cos(a), z=std::sin(a), yy=y?0.5f:-0.5f;
        v.push_back({x*0.5f,yy,z*0.5f,x,0,z,static_cast<float>(i)/sides,static_cast<float>(y)});
    }
    for(int i=0;i<sides;i++) {
        uint16_t a=static_cast<uint16_t>(i), b=static_cast<uint16_t>(i+sides+1);
        ix.insert(ix.end(),{a,static_cast<uint16_t>(a+1),b,b,static_cast<uint16_t>(a+1),static_cast<uint16_t>(b+1)});
    }
    mesh.upload(v,ix);
}

struct Pickup { float x,z; bool collected=false; };
struct RainDrop { float x,y,z,speed,lean; };
struct Splash { Vec3 p; float life; };
struct Puddle { float x,z,sx,sz; };

struct GameState {
    float playerX=0, playerZ=0, playerY=0, verticalVelocity=0;
    float yaw=0, gaitPhase=0, elapsed=0, health=100, hazardClock=0, lightningClock=0;
    float moveX=0, moveY=0;
    bool moving=false, sprintHeld=false, jumpRequest=false, jumping=false, paused=false, failed=false, completed=false;
    int score=0, supplies=0;
    std::array<Pickup,6> pickups{{{-2.3f,12,false},{2.4f,23,false},{-1.8f,36,false},
                                  {2.1f,49,false},{-2.5f,63,false},{1.8f,78,false}}};
    std::array<Puddle,11> puddles{{
        {-2.2f,8,0.9f,0.42f},{1.6f,16,1.0f,0.5f},{-0.4f,28,1.4f,0.55f},
        {2.6f,34,0.9f,0.42f},{-2.0f,43,1.0f,0.6f},{0.7f,52,1.5f,0.5f},
        {-2.7f,59,0.7f,0.4f},{2.1f,69,1.2f,0.5f},{-0.8f,76,1.3f,0.55f},
        {2.6f,88,1.0f,0.45f},{-2.3f,96,0.9f,0.45f}
    }};
    std::vector<RainDrop> rain;
    std::vector<Splash> splashes;
    std::mt19937 rng{0x4d4f4841u};
    float random(float a,float b) {
        return std::uniform_real_distribution<float>(a,b)(rng);
    }
    void reset() {
        playerX=playerZ=playerY=verticalVelocity=yaw=gaitPhase=elapsed=0;
        health=100; hazardClock=lightningClock=0; moveX=moveY=0;
        moving=sprintHeld=jumpRequest=jumping=paused=failed=completed=false;
        score=supplies=0;
        for (auto& p:pickups) p.collected=false;
        splashes.clear();
        if (rain.empty()) {
            rain.reserve(620);
            for(int i=0;i<620;i++) rain.push_back({random(-18,18),random(1,15),random(-18,26),random(11,19),random(-0.8f,0.8f)});
        }
    }
    void spawnSplash(float x,float z,int count=8) {
        for(int i=0;i<count && splashes.size()<180;i++)
            splashes.push_back({{x+random(-0.28f,0.28f),random(0.06f,0.32f),z+random(-0.25f,0.25f)},random(0.18f,0.48f)});
    }
    void update(float dt) {
        dt=clampf(dt,0,0.04f); elapsed+=dt;
        lightningClock-=dt;
        if (lightningClock<=0 && random(0,1)<dt*0.035f) lightningClock=random(0.08f,0.24f);
        for(auto& r:rain) {
            r.y-=r.speed*dt; r.x+=r.lean*dt; r.z-=1.7f*dt;
            if(r.y<0.02f) {
                if(std::abs(r.x-playerX)<12 && std::abs(r.z-playerZ)<12 && random(0,1)<0.08f)
                    spawnSplash(r.x,r.z,2);
                r.y=random(8,16); r.x=playerX+random(-17,17); r.z=playerZ+random(-14,25);
            }
            if(r.z<playerZ-20) r.z=playerZ+random(5,28);
            if(r.z>playerZ+30) r.z=playerZ-random(4,18);
            if(r.x<playerX-20) r.x=playerX+18;
            if(r.x>playerX+20) r.x=playerX-18;
        }
        for(auto& s:splashes) { s.life-=dt; s.p.y+=dt*0.9f; }
        splashes.erase(std::remove_if(splashes.begin(),splashes.end(),[](const Splash& s){return s.life<=0;}),splashes.end());
        if(paused || failed || completed) return;

        const float mag=std::sqrt(moveX*moveX+moveY*moveY);
        moving=mag>0.12f;
        if(moving) {
            const float norm=mag>1.0f?1.0f/mag:1.0f;
            const float dx=moveX*norm, dz=moveY*norm;
            const float speed=sprintHeld?5.15f:3.25f;
            playerX=clampf(playerX+dx*speed*dt,-3.75f,3.75f);
            playerZ+=dz*speed*dt;
            const float target=std::atan2(dx,dz);
            float diff=std::remainder(target-yaw,2.0f*PI);
            yaw+=diff*clampf(dt*10.0f,0,1);
            gaitPhase+=dt*speed*mag*2.8f;
            if(!jumping && std::abs(std::sin(gaitPhase*0.5f))>0.985f && random(0,1)<dt*4.0f)
                spawnSplash(playerX+random(-0.18f,0.18f),playerZ,4);
        }
        if(jumpRequest && !jumping) { verticalVelocity=5.0f; jumping=true; }
        jumpRequest=false;
        if(jumping) {
            playerY+=verticalVelocity*dt; verticalVelocity-=11.0f*dt;
            if(playerY<=0) { playerY=0; verticalVelocity=0; jumping=false; spawnSplash(playerX,playerZ,12); }
        }

        hazardClock-=dt;
        if(hazardClock<=0) {
            for(const auto& p:puddles) {
                if(std::abs(playerZ-p.z)<p.sz+0.24f && std::abs(playerX-p.x)<p.sx*0.72f) {
                    health-=6.0f; hazardClock=0.8f; spawnSplash(playerX,playerZ,10); break;
                }
            }
        }
        for(auto& p:pickups) {
            if(!p.collected && std::abs(playerZ-p.z)<1.1f && std::abs(playerX-p.x)<1.0f) {
                p.collected=true; supplies++; score+=100; health=std::min(100.0f,health+4.0f);
                spawnSplash(p.x,p.z,16);
            }
        }
        if(health<=0) { health=0; failed=true; }
        if(supplies>=static_cast<int>(pickups.size())) completed=true;
    }
};

struct Color { float r,g,b,a; };
struct UIVertex { float x,y,r,g,b,a; };

static std::array<uint8_t,7> glyph(char c) {
    switch(c) {
    case 'A': return {14,17,17,31,17,17,17};
    case 'B': return {30,17,17,30,17,17,30};
    case 'C': return {14,17,16,16,16,17,14};
    case 'D': return {30,17,17,17,17,17,30};
    case 'E': return {31,16,16,30,16,16,31};
    case 'F': return {31,16,16,30,16,16,16};
    case 'G': return {14,17,16,23,17,17,15};
    case 'H': return {17,17,17,31,17,17,17};
    case 'I': return {31,4,4,4,4,4,31};
    case 'J': return {7,2,2,2,18,18,12};
    case 'K': return {17,18,20,24,20,18,17};
    case 'L': return {16,16,16,16,16,16,31};
    case 'M': return {17,27,21,21,17,17,17};
    case 'N': return {17,25,21,19,17,17,17};
    case 'O': return {14,17,17,17,17,17,14};
    case 'P': return {30,17,17,30,16,16,16};
    case 'Q': return {14,17,17,17,21,18,13};
    case 'R': return {30,17,17,30,20,18,17};
    case 'S': return {15,16,16,14,1,1,30};
    case 'T': return {31,4,4,4,4,4,4};
    case 'U': return {17,17,17,17,17,17,14};
    case 'V': return {17,17,17,17,17,10,4};
    case 'W': return {17,17,17,21,21,21,10};
    case 'X': return {17,17,10,4,10,17,17};
    case 'Y': return {17,17,10,4,4,4,4};
    case 'Z': return {31,1,2,4,8,16,31};
    case '0': return {14,17,19,21,25,17,14};
    case '1': return {4,12,4,4,4,4,14};
    case '2': return {14,17,1,2,4,8,31};
    case '3': return {30,1,1,14,1,1,30};
    case '4': return {2,6,10,18,31,2,2};
    case '5': return {31,16,16,30,1,1,30};
    case '6': return {14,16,16,30,17,17,14};
    case '7': return {31,1,2,4,8,8,8};
    case '8': return {14,17,17,14,17,17,14};
    case '9': return {14,17,17,15,1,1,14};
    case ':': return {0,4,4,0,4,4,0};
    case '/': return {1,1,2,4,8,16,16};
    case '-': return {0,0,0,31,0,0,0};
    case '+': return {0,4,4,31,4,4,0};
    default: return {0,0,0,0,0,0,0};
    }
}

class UIBatch {
public:
    std::vector<UIVertex> verts;
    void clear() { verts.clear(); }
    void triangle(float x1,float y1,float x2,float y2,float x3,float y3,Color c) {
        verts.push_back({x1,y1,c.r,c.g,c.b,c.a}); verts.push_back({x2,y2,c.r,c.g,c.b,c.a});
        verts.push_back({x3,y3,c.r,c.g,c.b,c.a});
    }
    void rect(float x,float y,float w,float h,Color c) {
        triangle(x,y,x+w,y,x+w,y+h,c); triangle(x,y,x+w,y+h,x,y+h,c);
    }
    void circle(float cx,float cy,float radius,Color c,int segments=30) {
        for(int i=0;i<segments;i++) {
            float a0=(2.0f*PI*i)/segments, a1=(2.0f*PI*(i+1))/segments;
            triangle(cx,cy,cx+std::cos(a0)*radius,cy+std::sin(a0)*radius,
                     cx+std::cos(a1)*radius,cy+std::sin(a1)*radius,c);
        }
    }
    void text(const std::string& s,float x,float y,float scale,Color c) {
        float origin=x;
        for(char ch:s) {
            if(ch==' ') { x+=6.0f*scale; continue; }
            auto rows=glyph(ch);
            for(int row=0;row<7;row++) for(int col=0;col<5;col++)
                if((rows[row]>>(4-col))&1) rect(x+col*scale,y+row*scale,scale,scale,c);
            x+=6.0f*scale;
        }
        (void)origin;
    }
};

class RainAudio {
    SLObjectItf engineObject=nullptr, mixObject=nullptr, playerObject=nullptr;
    SLEngineItf engineInterface=nullptr;
    SLPlayItf playInterface=nullptr;
    SLAndroidSimpleBufferQueueItf queueInterface=nullptr;
    std::array<std::array<int16_t,4096>,2> buffers{};
    int nextFill=0;
    uint32_t rng=0x87a3c1f5u;
    float lowpass=0;
    static void callback(SLAndroidSimpleBufferQueueItf, void* context) {
        auto* self=static_cast<RainAudio*>(context);
        if(self) self->queueNext();
    }
    void fill(int index) {
        auto& b=buffers[static_cast<size_t>(index)];
        for(size_t i=0;i<b.size();i++) {
            rng=rng*1664525u+1013904223u;
            float white=static_cast<float>((rng>>16)&0xffffu)-32768.0f;
            lowpass=lowpass*0.91f+white*0.09f;
            float transient=((rng&0x7ffu)==11u)?(white*0.65f):0.0f;
            float value=(lowpass*0.38f+white*0.10f+transient)*0.72f;
            b[i]=static_cast<int16_t>(clampf(value,-30000,30000));
        }
    }
    void queueNext() {
        if(!queueInterface) return;
        fill(nextFill);
        (*queueInterface)->Enqueue(queueInterface,buffers[static_cast<size_t>(nextFill)].data(),
                                   static_cast<SLuint32>(buffers[0].size()*sizeof(int16_t)));
        nextFill=1-nextFill;
    }
public:
    void start() {
        if(slCreateEngine(&engineObject,0,nullptr,0,nullptr,nullptr)!=SL_RESULT_SUCCESS) return;
        if((*engineObject)->Realize(engineObject,SL_BOOLEAN_FALSE)!=SL_RESULT_SUCCESS) return;
        if((*engineObject)->GetInterface(engineObject,SL_IID_ENGINE,&engineInterface)!=SL_RESULT_SUCCESS) return;
        if((*engineInterface)->CreateOutputMix(engineInterface,&mixObject,0,nullptr,nullptr)!=SL_RESULT_SUCCESS) return;
        if((*mixObject)->Realize(mixObject,SL_BOOLEAN_FALSE)!=SL_RESULT_SUCCESS) return;
        SLDataLocator_AndroidSimpleBufferQueue locator={SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE,2};
        SLDataFormat_PCM format={SL_DATAFORMAT_PCM,1,SL_SAMPLINGRATE_22_05,SL_PCMSAMPLEFORMAT_FIXED_16,
            SL_PCMSAMPLEFORMAT_FIXED_16,SL_SPEAKER_FRONT_CENTER,SL_BYTEORDER_LITTLEENDIAN};
        SLDataSource source={&locator,&format};
        SLDataLocator_OutputMix sinkLocator={SL_DATALOCATOR_OUTPUTMIX,mixObject};
        SLDataSink sink={&sinkLocator,nullptr};
        const SLInterfaceID ids[]={SL_IID_ANDROIDSIMPLEBUFFERQUEUE};
        const SLboolean required[]={SL_BOOLEAN_TRUE};
        if((*engineInterface)->CreateAudioPlayer(engineInterface,&playerObject,&source,&sink,1,ids,required)!=SL_RESULT_SUCCESS) return;
        if((*playerObject)->Realize(playerObject,SL_BOOLEAN_FALSE)!=SL_RESULT_SUCCESS) return;
        if((*playerObject)->GetInterface(playerObject,SL_IID_PLAY,&playInterface)!=SL_RESULT_SUCCESS) return;
        if((*playerObject)->GetInterface(playerObject,SL_IID_ANDROIDSIMPLEBUFFERQUEUE,&queueInterface)!=SL_RESULT_SUCCESS) return;
        (*queueInterface)->RegisterCallback(queueInterface,callback,this);
        fill(0); fill(1);
        (*queueInterface)->Enqueue(queueInterface,buffers[0].data(),static_cast<SLuint32>(buffers[0].size()*sizeof(int16_t)));
        (*queueInterface)->Enqueue(queueInterface,buffers[1].data(),static_cast<SLuint32>(buffers[1].size()*sizeof(int16_t)));
        nextFill=0;
        (*playInterface)->SetPlayState(playInterface,SL_PLAYSTATE_PLAYING);
    }
    void stop() {
        if(playInterface) (*playInterface)->SetPlayState(playInterface,SL_PLAYSTATE_STOPPED);
        if(playerObject) { (*playerObject)->Destroy(playerObject); playerObject=nullptr; }
        queueInterface=nullptr; playInterface=nullptr;
        if(mixObject) { (*mixObject)->Destroy(mixObject); mixObject=nullptr; }
        if(engineObject) { (*engineObject)->Destroy(engineObject); engineObject=nullptr; }
        engineInterface=nullptr;
    }
    ~RainAudio(){ stop(); }
};

class Renderer {
    GLuint program=0, uiProgram=0, rainVbo=0, uiVbo=0;
    GLint aPos=-1,aNormal=-1,aUV=-1,uMVP=-1,uModel=-1,uTex=-1,uUseTex=-1,uTint=-1,uWet=-1,uAlpha=-1,uCamera=-1,uLight=-1;
    GLint uiPos=-1,uiColor=-1,uiViewport=-1;
    std::array<GLuint,TEX_COUNT> textures{};
    Mesh cube,sphere,plane,cylinder;
    UIBatch ui;
    Mat4 view=Mat4::identity(), proj=Mat4::identity(), vp=Mat4::identity();
    Vec3 camera{0,3,-6};
    int width=1280,height=720;
    bool ready=false;
    RiggedCharacter riggedPlayer;

    static GLuint compileShader(GLenum type,const char* source) {
        GLuint sh=glCreateShader(type); glShaderSource(sh,1,&source,nullptr); glCompileShader(sh);
        GLint ok=0; glGetShaderiv(sh,GL_COMPILE_STATUS,&ok);
        if(!ok) { char log[2048]{}; glGetShaderInfoLog(sh,sizeof(log),nullptr,log); MM_LOGE("Shader compile: %s",log); glDeleteShader(sh); return 0; }
        return sh;
    }
    static GLuint linkProgram(const char* vs,const char* fs) {
        GLuint v=compileShader(GL_VERTEX_SHADER,vs), f=compileShader(GL_FRAGMENT_SHADER,fs);
        if(!v||!f) { if(v)glDeleteShader(v); if(f)glDeleteShader(f); return 0; }
        GLuint p=glCreateProgram(); glAttachShader(p,v); glAttachShader(p,f);
        glBindAttribLocation(p,0,"aPosition"); glBindAttribLocation(p,1,"aNormal"); glBindAttribLocation(p,2,"aUV");
        glLinkProgram(p); glDeleteShader(v); glDeleteShader(f);
        GLint ok=0; glGetProgramiv(p,GL_LINK_STATUS,&ok);
        if(!ok) { char log[2048]{}; glGetProgramInfoLog(p,sizeof(log),nullptr,log); MM_LOGE("Shader link: %s",log); glDeleteProgram(p); return 0; }
        return p;
    }
    bool loadTexture(AAssetManager* mgr,const char* name,GLuint& tex) {
        AAsset* asset=AAssetManager_open(mgr,name,AASSET_MODE_BUFFER);
        std::vector<uint8_t> raw(static_cast<size_t>(TEX_SIZE*TEX_SIZE*3));
        bool loaded=false;
        if(asset) {
            const off_t n=AAsset_getLength(asset);
            if(n==static_cast<off_t>(raw.size())) {
                size_t offset=0;
                while(offset<raw.size()) {
                    int got=AAsset_read(asset,raw.data()+offset,raw.size()-offset);
                    if(got<=0) break;
                    offset+=static_cast<size_t>(got);
                }
                loaded=offset==raw.size();
            }
            AAsset_close(asset);
        }
        int srcSize=loaded?TEX_SIZE:256;
        if(!loaded) {
            raw.assign(static_cast<size_t>(srcSize*srcSize*3),0);
            for(int y=0;y<srcSize;y++) for(int x=0;x<srcSize;x++) {
                const size_t i=static_cast<size_t>((y*srcSize+x)*3);
                const bool c=((x/16+y/16)&1)!=0;
                raw[i]=c?150:80; raw[i+1]=c?80:100; raw[i+2]=c?55:130;
            }
            MM_LOGE("Texture asset missing or malformed: %s",name);
        }
        const int outSize=RENDER_TEX_SIZE;
        std::vector<uint8_t> reduced(static_cast<size_t>(outSize*outSize*3));
        if(srcSize==TEX_SIZE) {
            for(int y=0;y<outSize;y++) for(int x=0;x<outSize;x++) {
                const size_t d=static_cast<size_t>((y*outSize+x)*3);
                const size_t s=static_cast<size_t>(((y*2)*srcSize+x*2)*3);
                for(int c=0;c<3;c++) {
                    const int v=raw[s+c]+raw[s+3+c]+raw[s+srcSize*3+c]+raw[s+srcSize*3+3+c];
                    reduced[d+c]=static_cast<uint8_t>(v/4);
                }
            }
        } else {
            for(int y=0;y<outSize;y++) for(int x=0;x<outSize;x++) {
                int sx=x*srcSize/outSize, sy=y*srcSize/outSize;
                std::memcpy(&reduced[static_cast<size_t>((y*outSize+x)*3)],
                            &raw[static_cast<size_t>((sy*srcSize+sx)*3)],3);
            }
        }
        glGenTextures(1,&tex); glBindTexture(GL_TEXTURE_2D,tex);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGB,outSize,outSize,0,GL_RGB,GL_UNSIGNED_BYTE,reduced.data());
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_REPEAT);
        glGenerateMipmap(GL_TEXTURE_2D);
        return glGetError()==GL_NO_ERROR;
    }
    void createPrograms() {
        const char* vs=R"(precision mediump float;
attribute vec3 aPosition; attribute vec3 aNormal; attribute vec2 aUV;
uniform mat4 uMVP; uniform mat4 uModel;
varying vec3 vNormal; varying vec3 vWorld; varying vec2 vUV;
void main(){ vec4 w=uModel*vec4(aPosition,1.0); vWorld=w.xyz; vNormal=normalize(mat3(uModel)*aNormal); vUV=aUV; gl_Position=uMVP*vec4(aPosition,1.0); })";
        const char* fs=R"(precision mediump float;
uniform sampler2D uTexture; uniform int uUseTexture; uniform vec3 uTint;
uniform float uWet; uniform float uAlpha; uniform vec3 uCamera; uniform vec3 uLight;
varying vec3 vNormal; varying vec3 vWorld; varying vec2 vUV;
void main(){ vec3 base=uTint; if(uUseTexture==1) base*=texture2D(uTexture,vUV).rgb;
vec3 n=normalize(vNormal); vec3 l=normalize(-uLight); vec3 v=normalize(uCamera-vWorld);
float diffuse=max(dot(n,l),0.0); float spec=pow(max(dot(reflect(-l,n),v),0.0),20.0)*(0.04+uWet*0.40);
float ambient=0.34; vec3 col=base*(ambient+diffuse*0.72)+vec3(spec);
gl_FragColor=vec4(col,uAlpha); })";
        program=linkProgram(vs,fs);
        if(!program) return;
        aPos=glGetAttribLocation(program,"aPosition"); aNormal=glGetAttribLocation(program,"aNormal"); aUV=glGetAttribLocation(program,"aUV");
        uMVP=glGetUniformLocation(program,"uMVP"); uModel=glGetUniformLocation(program,"uModel");
        uTex=glGetUniformLocation(program,"uTexture"); uUseTex=glGetUniformLocation(program,"uUseTexture");
        uTint=glGetUniformLocation(program,"uTint"); uWet=glGetUniformLocation(program,"uWet");
        uAlpha=glGetUniformLocation(program,"uAlpha"); uCamera=glGetUniformLocation(program,"uCamera"); uLight=glGetUniformLocation(program,"uLight");
        const char* uvs=R"(precision mediump float; attribute vec2 aPosition; attribute vec4 aColor; uniform vec2 uViewport; varying vec4 vColor;
void main(){ vec2 p=vec2(aPosition.x/uViewport.x*2.0-1.0,1.0-aPosition.y/uViewport.y*2.0); gl_Position=vec4(p,0.0,1.0); vColor=aColor; })";
        const char* ufs=R"(precision mediump float; varying vec4 vColor; void main(){ gl_FragColor=vColor; })";
        uiProgram=glCreateProgram(); GLuint uv=compileShader(GL_VERTEX_SHADER,uvs), uf=compileShader(GL_FRAGMENT_SHADER,ufs);
        if(uv&&uf) { glAttachShader(uiProgram,uv); glAttachShader(uiProgram,uf); glBindAttribLocation(uiProgram,0,"aPosition"); glBindAttribLocation(uiProgram,1,"aColor"); glLinkProgram(uiProgram); }
        if(uv) glDeleteShader(uv); if(uf) glDeleteShader(uf);
        uiPos=glGetAttribLocation(uiProgram,"aPosition"); uiColor=glGetAttribLocation(uiProgram,"aColor"); uiViewport=glGetUniformLocation(uiProgram,"uViewport");
        glGenBuffers(1,&rainVbo); glGenBuffers(1,&uiVbo);
    }
    void setObjectUniforms(const Mat4& model,int texture,Vec3 tint,float wet,float alpha) {
        Mat4 mvp=vp*model;
        glUniformMatrix4fv(uMVP,1,GL_FALSE,mvp.m.data());
        glUniformMatrix4fv(uModel,1,GL_FALSE,model.m.data());
        glUniform3f(uTint,tint.x,tint.y,tint.z); glUniform1f(uWet,wet); glUniform1f(uAlpha,alpha);
        glUniform1i(uUseTex,texture>=0?1:0); glUniform1i(uTex,0);
        if(texture>=0 && texture<TEX_COUNT) { glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D,textures[static_cast<size_t>(texture)]); }
    }
    void draw(const Mesh& mesh,const Mat4& model,int texture,Vec3 tint,float wet=0,float alpha=1) {
        if(!program || !mesh.vbo || !mesh.ibo) return;
        setObjectUniforms(model,texture,tint,wet,alpha);
        glBindBuffer(GL_ARRAY_BUFFER,mesh.vbo); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,mesh.ibo);
        glEnableVertexAttribArray(static_cast<GLuint>(aPos)); glEnableVertexAttribArray(static_cast<GLuint>(aNormal)); glEnableVertexAttribArray(static_cast<GLuint>(aUV));
        glVertexAttribPointer(static_cast<GLuint>(aPos),3,GL_FLOAT,GL_FALSE,sizeof(Vertex),reinterpret_cast<void*>(offsetof(Vertex,px)));
        glVertexAttribPointer(static_cast<GLuint>(aNormal),3,GL_FLOAT,GL_FALSE,sizeof(Vertex),reinterpret_cast<void*>(offsetof(Vertex,nx)));
        glVertexAttribPointer(static_cast<GLuint>(aUV),2,GL_FLOAT,GL_FALSE,sizeof(Vertex),reinterpret_cast<void*>(offsetof(Vertex,u)));
        glDrawElements(mesh.mode,mesh.indexCount,GL_UNSIGNED_SHORT,nullptr);
    }
    Mat4 at(float x,float y,float z,float sx,float sy,float sz) {
        return Mat4::translation({x,y,z})*Mat4::scale({sx,sy,sz});
    }
    void drawBone(const Mat4& root,const Vec3& from,const Vec3& to,float radius,Vec3 tint,int texture) {
        const Vec3 d=to-from; const float len=length(d); if(len<0.001f) return;
        const float yaw=std::atan2(d.x,d.z);
        const float pitch=std::acos(clampf(d.y/len,-1,1));
        Mat4 model=root*Mat4::translation((from+to)*0.5f)*Mat4::rotateY(yaw)*Mat4::rotateX(pitch)*Mat4::scale({radius*2.0f,len+radius*0.55f,radius*2.0f});
        draw(sphere,model,texture,tint,0.05f,1);
    }
    void drawCharacter(const GameState& g) {
        Mat4 root=Mat4::translation({g.playerX,g.playerY,g.playerZ})*Mat4::rotateY(g.yaw);
        if(riggedPlayer.ready) {
            // Center the imported GLB and normalize its height to the existing player scale.
            riggedPlayer.update(g.elapsed);
            const float s=riggedPlayer.modelScale;
            Mat4 model=root*Mat4::translation({-riggedPlayer.centerX*s,-riggedPlayer.minY*s,-riggedPlayer.centerZ*s})*Mat4::scale({s,s,s});
            draw(riggedPlayer.mesh,model,-1,{0.62f,0.78f,0.82f},0.28f,1.0f);
            return;
        }
        draw(sphere,root*Mat4::translation({0,0.02f,0})*Mat4::scale({0.80f,0.035f,0.62f}),-1,{0.10f,0.13f,0.15f},0.8f,0.52f);
        float stride=g.moving?std::sin(g.gaitPhase)*0.24f:0;
        float bend=g.moving?std::max(0.0f,std::sin(g.gaitPhase+PI))*0.12f:0;
        float bob=g.moving?std::abs(std::sin(g.gaitPhase))*0.035f:0;
        Vec3 hipL(-0.145f,0.77f+bob,0), hipR(0.145f,0.77f+bob,0);
        Vec3 kneeL(-0.15f,0.43f+bob,stride), kneeR(0.15f,0.43f+bob,-stride);
        Vec3 ankleL(-0.15f,0.12f+bob,stride*1.48f+bend), ankleR(0.15f,0.12f+bob,-stride*1.48f+bend);
        Vec3 pants{0.20f,0.24f,0.25f}, skin{0.57f,0.34f,0.23f}, shirt{0.11f,0.30f,0.42f};
        draw(sphere,root*Mat4::translation({0,0.78f+bob,0})*Mat4::scale({0.38f,0.25f,0.30f}),6,pants,0.04f);
        drawBone(root,hipL,kneeL,0.105f,pants,6); drawBone(root,kneeL,ankleL,0.078f,pants,6);
        drawBone(root,hipR,kneeR,0.105f,pants,6); drawBone(root,kneeR,ankleR,0.078f,pants,6);
        draw(sphere,root*Mat4::translation({ankleL.x,ankleL.y-0.035f,ankleL.z+0.075f})*Mat4::scale({0.20f,0.10f,0.30f}),-1,{0.075f,0.075f,0.08f});
        draw(sphere,root*Mat4::translation({ankleR.x,ankleR.y-0.035f,ankleR.z+0.075f})*Mat4::scale({0.20f,0.10f,0.30f}),-1,{0.075f,0.075f,0.08f});
        draw(sphere,root*Mat4::translation({0,1.11f+bob,0})*Mat4::scale({0.45f,0.49f,0.29f}),6,shirt,0.02f);
        draw(sphere,root*Mat4::translation({0,1.09f+bob,-0.205f})*Mat4::scale({0.31f,0.39f,0.15f}),6,{0.18f,0.20f,0.22f});
        float armSwing=g.moving?std::sin(g.gaitPhase+PI)*0.28f:0;
        Vec3 shL(-0.26f,1.30f+bob,0), shR(0.26f,1.30f+bob,0);
        Vec3 elL(-0.34f,1.06f+bob,armSwing), elR(0.34f,1.06f+bob,-armSwing);
        Vec3 handL(-0.34f,0.88f+bob,armSwing*1.5f), handR(0.34f,0.88f+bob,-armSwing*1.5f);
        drawBone(root,shL,elL,0.082f,shirt,6); drawBone(root,elL,handL,0.066f,shirt,6);
        drawBone(root,shR,elR,0.082f,shirt,6); drawBone(root,elR,handR,0.066f,shirt,6);
        draw(sphere,root*Mat4::translation({handL.x,handL.y,handL.z})*Mat4::scale({0.115f,0.12f,0.12f}),-1,skin);
        draw(sphere,root*Mat4::translation({handR.x,handR.y,handR.z})*Mat4::scale({0.115f,0.12f,0.12f}),-1,skin);
        draw(sphere,root*Mat4::translation({0,1.57f+bob,0.015f})*Mat4::scale({0.235f,0.27f,0.225f}),-1,skin);
        draw(sphere,root*Mat4::translation({0,1.755f+bob,-0.01f})*Mat4::scale({0.245f,0.105f,0.235f}),-1,{0.07f,0.055f,0.045f});
        draw(sphere,root*Mat4::translation({-0.235f,1.56f+bob,0.02f})*Mat4::scale({0.055f,0.095f,0.07f}),-1,skin);
        draw(sphere,root*Mat4::translation({0.235f,1.56f+bob,0.02f})*Mat4::scale({0.055f,0.095f,0.07f}),-1,skin);
        draw(sphere,root*Mat4::translation({-0.078f,1.59f+bob,0.213f})*Mat4::scale({0.036f,0.034f,0.018f}),-1,{0.04f,0.035f,0.03f});
        draw(sphere,root*Mat4::translation({0.078f,1.59f+bob,0.213f})*Mat4::scale({0.036f,0.034f,0.018f}),-1,{0.04f,0.035f,0.03f});
    }
    void drawHouse(float side,float centerZ,int variant) {
        const float cx=side*(9.3f+0.18f*std::sin(static_cast<float>(variant)*2.1f));
        const int wallTex=(variant%3==0)?2:1, roofTex=4;
        const Vec3 wallTint=(variant%3==0)?Vec3(0.80f,0.66f,0.55f):Vec3(0.92f,0.84f,0.70f);
        draw(cube,at(cx,1.25f,centerZ,4.2f,2.5f,8.2f),wallTex,wallTint,0.0f);
        draw(cube,trs({cx-0.64f,2.86f,centerZ},{0,0,0.43f},{2.55f,0.15f,8.55f}),roofTex,{0.82f,0.43f,0.28f},0.12f);
        draw(cube,trs({cx+0.64f,2.86f,centerZ},{0,0,-0.43f},{2.55f,0.15f,8.55f}),roofTex,{0.76f,0.34f,0.23f},0.12f);
        const float face=cx-side*2.12f;
        draw(cube,at(face-side*0.045f,0.93f,centerZ+0.9f,0.09f,1.72f,0.86f),5,{0.22f,0.13f,0.085f},0);
        draw(cube,at(face-side*0.055f,1.67f,centerZ-1.75f,0.10f,0.91f,1.12f),5,{0.28f,0.29f,0.25f},0);
        draw(cube,at(face-side*0.08f,1.67f,centerZ-1.75f,0.025f,0.73f,0.92f),-1,{0.16f,0.31f,0.34f},0.2f);
        draw(cube,at(face-side*0.055f,1.67f,centerZ+3.0f,0.10f,0.91f,1.12f),5,{0.28f,0.29f,0.25f},0);
        draw(cube,at(face-side*0.08f,1.67f,centerZ+3.0f,0.025f,0.73f,0.92f),-1,{0.14f,0.28f,0.31f},0.25f);
        draw(cube,at(cx,0.12f,centerZ,4.35f,0.15f,8.35f),5,{0.47f,0.43f,0.38f},0.08f);
        if(variant%2==0) {
            draw(cube,trs({face-side*0.07f,2.2f,centerZ+0.9f},{0,0,0},{0.08f,0.08f,1.05f}),-1,{0.90f,0.72f,0.28f});
        }
    }
    void drawWorld(const GameState& g) {
        const float z=g.playerZ;
        draw(plane,Mat4::translation({0,-0.08f,z+45.0f})*Mat4::scale({70,1,170}),3,{0.52f,0.46f,0.36f},0.08f);
        draw(plane,Mat4::translation({0,0.0f,z+45.0f})*Mat4::scale({9.9f,1,170}),0,{0.68f,0.70f,0.71f},0.86f);
        draw(plane,Mat4::translation({-7.3f,0.008f,z+45.0f})*Mat4::scale({4.4f,1,170}),3,{0.60f,0.53f,0.41f},0.05f);
        draw(plane,Mat4::translation({7.3f,0.008f,z+45.0f})*Mat4::scale({4.4f,1,170}),3,{0.60f,0.53f,0.41f},0.05f);
        const int first=static_cast<int>(std::floor(z/18.0f))-3;
        for(int k=first;k<first+9;k++) {
            const float hz=static_cast<float>(k)*18.0f+9.0f;
            if(std::abs(hz-z)<82.0f) {
                drawHouse(-1,hz,k); drawHouse(1,hz,k+1);
            }
        }
        for(int k=-28;k<=28;k++) {
            const float pz=std::floor(z/2.4f)*2.4f+static_cast<float>(k)*2.4f;
            if(std::abs(pz-z)>70.0f) continue;
            draw(cube,at(-5.05f,0.10f,pz,0.22f,0.20f,2.30f),5,{0.48f,0.48f,0.44f},0.25f);
            draw(cube,at(5.05f,0.10f,pz,0.22f,0.20f,2.30f),5,{0.48f,0.48f,0.44f},0.25f);
        }
        const int poleBase=static_cast<int>(std::floor(z/28.0f))-2;
        for(int k=poleBase;k<poleBase+6;k++) {
            float pz=static_cast<float>(k)*28.0f+7.0f;
            for(float side:{-1.0f,1.0f}) {
                draw(cylinder,at(side*5.65f,1.95f,pz,0.12f,3.9f,0.12f),5,{0.30f,0.27f,0.22f},0.05f);
                draw(cube,at(side*5.65f,3.75f,pz,0.95f,0.07f,0.09f),5,{0.28f,0.25f,0.21f},0);
                draw(cube,at(side*5.65f,4.08f,pz+14.0f,0.035f,0.035f,28.0f),-1,{0.035f,0.04f,0.045f},0.15f);
            }
            draw(cube,at(0,4.02f,pz+14.0f,11.3f,0.025f,0.025f),-1,{0.045f,0.047f,0.052f},0.15f);
        }
        for(const auto& p:g.puddles) {
            float dz=p.z-z;
            if(std::abs(dz)>95) continue;
            draw(sphere,Mat4::translation({p.x,0.028f,p.z})*Mat4::scale({p.sx*2.0f,0.025f,p.sz*2.0f}),0,{0.36f,0.43f,0.47f},1.0f,0.86f);
            if(std::abs(dz)<30) {
                draw(sphere,Mat4::translation({p.x+0.23f,0.046f,p.z-0.06f})*Mat4::scale({p.sx*0.9f,0.009f,p.sz*0.8f}),-1,{0.60f,0.69f,0.73f},0.9f,0.28f);
            }
        }
        for(const auto& p:g.pickups) {
            if(p.collected || std::abs(p.z-z)>95) continue;
            const float pulse=0.04f*std::sin(g.elapsed*3.1f+p.z);
            draw(cube,Mat4::translation({p.x,0.30f,p.z})*Mat4::scale({0.62f,0.52f,0.56f}),5,{0.61f,0.34f,0.15f},0.08f);
            draw(cube,Mat4::translation({p.x,0.57f,p.z})*Mat4::scale({0.65f,0.055f,0.59f}),5,{0.75f,0.48f,0.21f},0.08f);
            draw(cube,Mat4::translation({p.x,0.31f,p.z+0.288f})*Mat4::scale({0.29f,0.055f,0.018f}),-1,{0.92f,0.86f,0.65f});
            draw(cube,Mat4::translation({p.x,0.31f,p.z+0.288f})*Mat4::scale({0.055f,0.29f,0.018f}),-1,{0.92f,0.86f,0.65f});
            draw(sphere,Mat4::translation({p.x,0.78f+pulse,p.z})*Mat4::scale({0.13f,0.13f,0.13f}),-1,{1.0f,0.70f,0.22f},0.5f,0.8f);
        }
        for(const auto& s:g.splashes) {
            draw(sphere,Mat4::translation(s.p)*Mat4::scale({0.035f+s.life*0.07f,0.045f+s.life*0.08f,0.035f+s.life*0.07f}),-1,{0.55f,0.68f,0.76f},0.8f,clampf(s.life*2.0f,0.1f,0.8f));
        }
        drawCharacter(g);
        std::vector<Vertex> rv; rv.reserve(g.rain.size()*2);
        for(const auto& r:g.rain) {
            rv.push_back({r.x,r.y,r.z,0,0,1,0,0});
            rv.push_back({r.x+r.lean*0.035f,r.y-0.40f,r.z-0.06f,0,0,1,0,0});
        }
        glUseProgram(program);
        Mat4 id=Mat4::identity(); setObjectUniforms(id,-1,{0.68f,0.77f,0.84f},0.35f,0.55f);
        glBindBuffer(GL_ARRAY_BUFFER,rainVbo); glBufferData(GL_ARRAY_BUFFER,rv.size()*sizeof(Vertex),rv.data(),GL_DYNAMIC_DRAW);
        glEnableVertexAttribArray(static_cast<GLuint>(aPos)); glEnableVertexAttribArray(static_cast<GLuint>(aNormal)); glEnableVertexAttribArray(static_cast<GLuint>(aUV));
        glVertexAttribPointer(static_cast<GLuint>(aPos),3,GL_FLOAT,GL_FALSE,sizeof(Vertex),reinterpret_cast<void*>(offsetof(Vertex,px)));
        glVertexAttribPointer(static_cast<GLuint>(aNormal),3,GL_FLOAT,GL_FALSE,sizeof(Vertex),reinterpret_cast<void*>(offsetof(Vertex,nx)));
        glVertexAttribPointer(static_cast<GLuint>(aUV),2,GL_FLOAT,GL_FALSE,sizeof(Vertex),reinterpret_cast<void*>(offsetof(Vertex,u)));
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA); glDepthMask(GL_FALSE);
        glDrawArrays(GL_LINES,0,static_cast<GLsizei>(rv.size()));
        glDepthMask(GL_TRUE); glDisable(GL_BLEND);
    }
    void drawUI(const GameState& g) {
        const float h=static_cast<float>(height), w=static_cast<float>(width);
        ui.clear();
        const Color panel{0.035f,0.07f,0.09f,0.82f}, white{0.93f,0.96f,0.93f,1}, pale{0.66f,0.82f,0.83f,1};
        const Color teal{0.08f,0.74f,0.67f,0.98f}, red{0.86f,0.20f,0.17f,0.95f}, dark{0.04f,0.07f,0.08f,0.70f};
        ui.rect(18,18,300,118,panel);
        ui.text("MOHALLA MONSOON",32,31,2.15f,white);
        ui.text("SUPPLIES",32,53,1.9f,pale);
        ui.text(std::to_string(g.supplies)+"/6",155,53,2.0f,white);
        ui.text("SCORE",32,73,1.7f,pale); ui.text(std::to_string(g.score),95,73,1.8f,white);
        ui.text("HEALTH",32,94,1.7f,pale);
        ui.rect(110,96,175,13,{0.10f,0.13f,0.14f,0.98f});
        ui.rect(110,96,175*(g.health/100.0f),13,g.health>35?teal:red);
        const float joyX=w*0.17f, joyY=h*0.79f, joyR=h*0.115f;
        ui.circle(joyX,joyY,joyR,{0.08f,0.12f,0.14f,0.45f});
        ui.circle(joyX,joyY,joyR*0.98f,{0.69f,0.82f,0.84f,0.45f});
        ui.circle(joyX+g.moveX*joyR*0.48f,joyY-g.moveY*joyR*0.48f,joyR*0.40f,{0.12f,0.53f,0.52f,0.82f});
        const float runX=w*0.75f, jumpX=w*0.87f, btnY=h*0.79f, r=h*0.070f;
        ui.circle(runX,btnY,r,dark); ui.circle(runX,btnY,r*0.82f,{0.19f,0.55f,0.56f,0.76f});
        ui.text("RUN",runX-r*0.50f,btnY-r*0.18f,1.5f,white);
        ui.circle(jumpX,btnY,r*1.12f,dark); ui.circle(jumpX,btnY,r*0.88f,{0.78f,0.49f,0.18f,0.88f});
        ui.text("JUMP",jumpX-r*0.72f,btnY-r*0.18f,1.5f,white);
        ui.rect(w-64,18,46,42,panel); ui.rect(w-49,29,5,20,white); ui.rect(w-37,29,5,20,white);
        if(g.paused || g.failed || g.completed) {
            ui.rect(0,0,w,h,{0.01f,0.025f,0.035f,0.73f});
            ui.rect(w*0.27f,h*0.27f,w*0.46f,h*0.46f,{0.035f,0.09f,0.11f,0.96f});
            if(g.paused) ui.text("PAUSED",w*0.43f,h*0.36f,3.3f,white);
            else if(g.completed) ui.text("MISSION COMPLETE",w*0.34f,h*0.35f,2.45f,teal);
            else ui.text("MISSION FAILED",w*0.36f,h*0.35f,2.6f,red);
            ui.text("SUPPLIES",w*0.42f,h*0.47f,2.0f,pale);
            ui.text(std::to_string(g.supplies)+"/6",w*0.58f,h*0.47f,2.0f,white);
            ui.rect(w*0.41f,h*0.59f,w*0.18f,h*0.075f,{0.09f,0.55f,0.49f,0.96f});
            ui.text(g.paused?"RESUME":"RETRY",w*0.455f,h*0.61f,2.1f,white);
        }
        glUseProgram(uiProgram);
        glUniform2f(uiViewport,static_cast<float>(width),static_cast<float>(height));
        glBindBuffer(GL_ARRAY_BUFFER,uiVbo);
        glBufferData(GL_ARRAY_BUFFER,ui.verts.size()*sizeof(UIVertex),ui.verts.data(),GL_DYNAMIC_DRAW);
        glEnableVertexAttribArray(static_cast<GLuint>(uiPos)); glEnableVertexAttribArray(static_cast<GLuint>(uiColor));
        glVertexAttribPointer(static_cast<GLuint>(uiPos),2,GL_FLOAT,GL_FALSE,sizeof(UIVertex),reinterpret_cast<void*>(offsetof(UIVertex,x)));
        glVertexAttribPointer(static_cast<GLuint>(uiColor),4,GL_FLOAT,GL_FALSE,sizeof(UIVertex),reinterpret_cast<void*>(offsetof(UIVertex,r)));
        glDisable(GL_DEPTH_TEST); glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
        glDrawArrays(GL_TRIANGLES,0,static_cast<GLsizei>(ui.verts.size()));
        glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST);
    }
public:
    void initialize(AAssetManager* assets) {
        createPrograms();
        makeCube(cube); makeSphere(sphere); makePlane(plane); makeCylinder(cylinder);
        const char* names[TEX_COUNT]={"textures/asphalt.rgbtex","textures/plaster.rgbtex","textures/brick.rgbtex",
            "textures/mud.rgbtex","textures/clay_roof.rgbtex","textures/concrete.rgbtex","textures/fabric.rgbtex"};
        for(int i=0;i<TEX_COUNT;i++) loadTexture(assets,names[i],textures[static_cast<size_t>(i)]);
        riggedPlayer.load(assets,"characters/player.glb");
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
        ready=(program!=0 && uiProgram!=0);
        MM_LOGI("Renderer initialized: %s",ready?"OpenGL ES 2.0 ready":"shader error");
    }
    void resize(int w,int h) {
        width=std::max(1,w); height=std::max(1,h); glViewport(0,0,width,height);
    }
    void render(const GameState& g) {
        if(!ready) { glClearColor(0.08f,0.12f,0.16f,1); glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT); return; }
        const float flash=g.lightningClock>0?std::min(0.24f,g.lightningClock)*0.65f:0.0f;
        glClearColor(0.055f+flash,0.09f+flash,0.14f+flash,1);
        glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        const Vec3 forward{std::sin(g.yaw),0,std::cos(g.yaw)};
        camera={g.playerX-forward.x*6.8f, g.playerY+3.25f, g.playerZ-forward.z*6.8f};
        Vec3 look={g.playerX+forward.x*2.7f, g.playerY+1.0f, g.playerZ+forward.z*2.7f};
        view=Mat4::lookAt(camera,look,{0,1,0});
        proj=Mat4::perspective(68.0f*PI/180.0f,static_cast<float>(width)/std::max(1,height),0.08f,190.0f);
        vp=proj*view;
        glUseProgram(program);
        glUniform3f(uCamera,camera.x,camera.y,camera.z); glUniform3f(uLight,-0.45f,-1.0f,-0.25f);
        drawWorld(g);
        drawUI(g);
    }
    void destroy() {
        cube.destroy(); sphere.destroy(); plane.destroy(); cylinder.destroy();
        riggedPlayer.destroy();
        if(program) glDeleteProgram(program); if(uiProgram) glDeleteProgram(uiProgram);
        if(rainVbo) glDeleteBuffers(1,&rainVbo); if(uiVbo) glDeleteBuffers(1,&uiVbo);
        for(auto& t:textures) if(t) glDeleteTextures(1,&t);
        program=uiProgram=rainVbo=uiVbo=0; textures.fill(0); ready=false;
    }
};

struct Engine {
    android_app* app=nullptr;
    EGLDisplay display=EGL_NO_DISPLAY;
    EGLSurface surface=EGL_NO_SURFACE;
    EGLContext context=EGL_NO_CONTEXT;
    Renderer renderer;
    GameState game;
    RainAudio audio;
    bool initialized=false, audioStarted=false, joystickActive=false;
    float width=1280,height=720,joyCenterX=0,joyCenterY=0,joyRadius=100;
    int joyPointer=-1;
    bool sprintPointer=false;
    std::chrono::steady_clock::time_point previous=std::chrono::steady_clock::now();

    bool initDisplay(ANativeWindow* window) {
        display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if(display==EGL_NO_DISPLAY) return false;
        EGLint major=0,minor=0;
        if(!eglInitialize(display,&major,&minor)) return false;
        const EGLint attribs[]={EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_SURFACE_TYPE,EGL_WINDOW_BIT,
            EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_DEPTH_SIZE,16,EGL_NONE};
        EGLConfig config{}; EGLint count=0;
        if(!eglChooseConfig(display,attribs,&config,1,&count)||count==0) return false;
        EGLint format=0; eglGetConfigAttrib(display,config,EGL_NATIVE_VISUAL_ID,&format);
        ANativeWindow_setBuffersGeometry(window,0,0,format);
        const EGLint ctxAttrs[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
        context=eglCreateContext(display,config,EGL_NO_CONTEXT,ctxAttrs);
        surface=eglCreateWindowSurface(display,config,window,nullptr);
        if(context==EGL_NO_CONTEXT||surface==EGL_NO_SURFACE) return false;
        if(!eglMakeCurrent(display,surface,surface,context)) return false;
        EGLint w=0,h=0; eglQuerySurface(display,surface,EGL_WIDTH,&w); eglQuerySurface(display,surface,EGL_HEIGHT,&h);
        width=static_cast<float>(std::max(1,w)); height=static_cast<float>(std::max(1,h));
        renderer.initialize(app->activity->assetManager); renderer.resize(w,h);
        initialized=true; previous=std::chrono::steady_clock::now();
        if(!audioStarted) { audio.start(); audioStarted=true; }
        MM_LOGI("Display ready %dx%d",w,h);
        return true;
    }
    void termDisplay() {
        if(display!=EGL_NO_DISPLAY) {
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
            renderer.destroy();
            if(surface!=EGL_NO_SURFACE) eglDestroySurface(display,surface);
            if(context!=EGL_NO_CONTEXT) eglDestroyContext(display,context);
            eglTerminate(display);
        }
        display=EGL_NO_DISPLAY; surface=EGL_NO_SURFACE; context=EGL_NO_CONTEXT; initialized=false;
    }
    void updateJoy(float x,float y) {
        const float dx=(x-joyCenterX)/joyRadius, dy=(joyCenterY-y)/joyRadius;
        float m=std::sqrt(dx*dx+dy*dy);
        if(m>1) { game.moveX=dx/m; game.moveY=dy/m; }
        else { game.moveX=dx; game.moveY=dy; }
    }
    int32_t input(AInputEvent* event) {
        if(AInputEvent_getType(event)!=AINPUT_EVENT_TYPE_MOTION) return 0;
        const int action=AMotionEvent_getAction(event);
        const int masked=action&AMOTION_EVENT_ACTION_MASK;
        const size_t n=AMotionEvent_getPointerCount(event);
        const float w=width,h=height;
        joyCenterX=w*0.17f; joyCenterY=h*0.79f; joyRadius=h*0.12f;
        if(masked==AMOTION_EVENT_ACTION_DOWN || masked==AMOTION_EVENT_ACTION_POINTER_DOWN) {
            const int index=(action&AMOTION_EVENT_ACTION_POINTER_INDEX_MASK)>>AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT;
            const float x=AMotionEvent_getX(event,index), y=AMotionEvent_getY(event,index);
            const int id=AMotionEvent_getPointerId(event,index);
            if((x-joyCenterX)*(x-joyCenterX)+(y-joyCenterY)*(y-joyCenterY)<joyRadius*joyRadius*2.0f) {
                joystickActive=true; joyPointer=id; updateJoy(x,y); return 1;
            }
            if(x>w*0.80f && y>h*0.55f) {
                if(game.failed||game.completed) game.reset();
                else if(game.paused) game.paused=false;
                else game.jumpRequest=true;
                return 1;
            }
            if(x>w*0.68f && x<w*0.82f && y>h*0.59f) { sprintPointer=true; game.sprintHeld=true; return 1; }
            if(x>w*0.90f && y<h*0.23f) {
                if(game.failed||game.completed) game.reset();
                else game.paused=!game.paused;
                return 1;
            }
            if((game.failed||game.completed||game.paused) && x>w*0.27f && x<w*0.73f && y>h*0.27f && y<h*0.76f) {
                if(game.paused) game.paused=false; else game.reset();
                return 1;
            }
        } else if(masked==AMOTION_EVENT_ACTION_MOVE) {
            if(joystickActive) {
                bool found=false;
                for(size_t i=0;i<n;i++) if(AMotionEvent_getPointerId(event,i)==joyPointer) {
                    updateJoy(AMotionEvent_getX(event,i),AMotionEvent_getY(event,i)); found=true; break;
                }
                if(!found) { joystickActive=false; game.moveX=game.moveY=0; }
            }
            return 1;
        } else if(masked==AMOTION_EVENT_ACTION_UP || masked==AMOTION_EVENT_ACTION_POINTER_UP || masked==AMOTION_EVENT_ACTION_CANCEL) {
            const int index=(action&AMOTION_EVENT_ACTION_POINTER_INDEX_MASK)>>AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT;
            const int id=(index>=0 && static_cast<size_t>(index)<n)?AMotionEvent_getPointerId(event,index):-1;
            if(id==joyPointer || masked==AMOTION_EVENT_ACTION_CANCEL) { joystickActive=false; joyPointer=-1; game.moveX=game.moveY=0; }
            if(sprintPointer) { sprintPointer=false; game.sprintHeld=false; }
            return 1;
        }
        return 1;
    }
};

static void handleAppCmd(android_app* app,int32_t cmd) {
    auto* e=static_cast<Engine*>(app->userData);
    if(!e) return;
    switch(cmd) {
    case APP_CMD_INIT_WINDOW:
        if(app->window && !e->initialized) {
            if(!e->initDisplay(app->window)) MM_LOGE("Failed to initialize EGL display");
        }
        break;
    case APP_CMD_TERM_WINDOW:
        if(e->initialized) e->termDisplay();
        break;
    case APP_CMD_LOST_FOCUS:
        e->game.moveX=e->game.moveY=0; e->game.sprintHeld=false; break;
    default: break;
    }
}
static int32_t handleInput(android_app* app,AInputEvent* event) {
    auto* e=static_cast<Engine*>(app->userData);
    return e?e->input(event):0;
}
} // namespace mm

void android_main(android_app* app) {
    mm::Engine engine;
    engine.app=app; app->userData=&engine; app->onAppCmd=mm::handleAppCmd; app->onInputEvent=mm::handleInput;
    engine.game.reset();
    while(!app->destroyRequested) {
        int events=0; android_poll_source* source=nullptr;
        int ident;
        while((ident=ALooper_pollOnce(engine.initialized?0:-1,nullptr,&events,reinterpret_cast<void**>(&source)))>=0) {
            if(source) source->process(app,source);
            if(app->destroyRequested) break;
        }
        if(engine.initialized && engine.display!=EGL_NO_DISPLAY && engine.surface!=EGL_NO_SURFACE) {
            const auto now=std::chrono::steady_clock::now();
            const float dt=std::chrono::duration<float>(now-engine.previous).count();
            engine.previous=now;
            engine.game.update(dt);
            engine.renderer.render(engine.game);
            if(eglSwapBuffers(engine.display,engine.surface)==EGL_FALSE) {
                EGLint err=eglGetError();
                if(err==EGL_BAD_SURFACE || err==EGL_CONTEXT_LOST) engine.termDisplay();
            }
        }
    }
    if(engine.initialized) engine.termDisplay();
    engine.audio.stop();
}