/*
 * GPUPixel
 *
 * Created by PixPark on 2021/6/24.
 * Copyright © 2021 PixPark. All rights reserved.
 */

#include "gpupixel/source/source_yuv.h"
#include "core/gpupixel_context.h"
#include "utils/util.h"

namespace gpupixel {

const std::string kYUVVertexShaderString = R"(
    attribute vec4 position;
    attribute vec4 inputTextureCoordinate;
    varying vec2 textureCoordinate;

    void main() {
      textureCoordinate = (inputTextureCoordinate).xy;
      gl_Position = position;
    })";

#if defined(GPUPIXEL_GLES_SHADER)
const std::string kYUVFragmentShaderString = R"(
    varying mediump vec2 textureCoordinate;
    uniform sampler2D yTexture;
    uniform sampler2D uTexture;
    uniform sampler2D vTexture;

    void main() {
      mediump float y = texture2D(yTexture, textureCoordinate).r;
      mediump float u = texture2D(uTexture, textureCoordinate).r - 0.5;
      mediump float v = texture2D(vTexture, textureCoordinate).r - 0.5;

      mediump float r = y + 1.402 * v;
      mediump float g = y - 0.344136 * u - 0.714136 * v;
      mediump float b = y + 1.772 * u;

      gl_FragColor = vec4(r, g, b, 1.0);
    })";
#elif defined(GPUPIXEL_GL_SHADER)
const std::string kYUVFragmentShaderString = R"(
    varying vec2 textureCoordinate;
    uniform sampler2D yTexture;
    uniform sampler2D uTexture;
    uniform sampler2D vTexture;

    void main() {
      float y = texture2D(yTexture, textureCoordinate).r;
      float u = texture2D(uTexture, textureCoordinate).r - 0.5;
      float v = texture2D(vTexture, textureCoordinate).r - 0.5;

      float r = y + 1.402 * v;
      float g = y - 0.344136 * u - 0.714136 * v;
      float b = y + 1.772 * u;

      gl_FragColor = vec4(r, g, b, 1.0);
    })";
#endif

std::shared_ptr<SourceYUV> SourceYUV::Create() {
  auto ret = std::shared_ptr<SourceYUV>(new SourceYUV());
  gpupixel::GPUPixelContext::GetInstance()->SyncRunWithContext([&] {
    if (!ret->Init()) {
      return ret.reset();
    }
  });
  return ret;
}

SourceYUV::SourceYUV() {}

SourceYUV::~SourceYUV() {
  GPUPixelContext::GetInstance()->SyncRunWithContext([=] {
    if (y_texture_) glDeleteTextures(1, &y_texture_);
    if (u_texture_) glDeleteTextures(1, &u_texture_);
    if (v_texture_) glDeleteTextures(1, &v_texture_);
  });
}

bool SourceYUV::Init() {
  filter_program_ = GPUPixelGLProgram::CreateWithShaderString(
      kYUVVertexShaderString, kYUVFragmentShaderString);
  GPUPixelContext::GetInstance()->SetActiveGlProgram(filter_program_);

  filter_position_attribute_ = filter_program_->GetAttribLocation("position");
  filter_tex_coord_attribute_ =
      filter_program_->GetAttribLocation("inputTextureCoordinate");

  if (0 == y_texture_) {
    glGenTextures(1, &y_texture_);
  }
  if (0 == u_texture_) {
    glGenTextures(1, &u_texture_);
  }
  if (0 == v_texture_) {
    glGenTextures(1, &v_texture_);
  }

  glBindTexture(GL_TEXTURE_2D, y_texture_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

  glBindTexture(GL_TEXTURE_2D, u_texture_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

  glBindTexture(GL_TEXTURE_2D, v_texture_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

  return true;
}

void SourceYUV::SetRotation(RotationMode rotation) {
  rotation_ = rotation;
}

void SourceYUV::ProcessData(const uint8_t* y_data,
                            const uint8_t* u_data,
                            const uint8_t* v_data,
                            int width,
                            int height,
                            RotationMode rotation) {
  GPUPixelContext::GetInstance()->SyncRunWithContext(
      [=] { GenerateTextureWithPixels(y_data, u_data, v_data, width, height, rotation); });
}

int SourceYUV::GenerateTextureWithPixels(const uint8_t* y_data,
                                         const uint8_t* u_data,
                                         const uint8_t* v_data,
                                         int width,
                                         int height,
                                         RotationMode rotation) {
  int tex_width = width;
  int tex_height = height;

  if (!framebuffer_ || (framebuffer_->GetWidth() != tex_width ||
                        framebuffer_->GetHeight() != tex_height)) {
    framebuffer_ = GPUPixelContext::GetInstance()
                       ->GetFramebufferFactory()
                       ->CreateFramebuffer(tex_width, tex_height);
  }
  this->SetFramebuffer(framebuffer_, NoRotation);

  int uv_width = (tex_width + 1) / 2;
  int uv_height = (tex_height + 1) / 2;

  // 尺寸相同时用 glTexSubImage2D 复用已分配显存（避免每帧重新分配 GPU 内存）；
  // 尺寸变化（首帧或分辨率切换）时才用 glTexImage2D 重新分配。
  const bool size_changed = (tex_width != tex_width_) || (tex_height != tex_height_);
  if (size_changed) {
    tex_width_ = tex_width;
    tex_height_ = tex_height;
  }

  GL_CALL(glBindTexture(GL_TEXTURE_2D, y_texture_));
  if (size_changed) {
    GL_CALL(glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, tex_width, tex_height, 0,
                         GL_LUMINANCE, GL_UNSIGNED_BYTE, y_data));
  } else {
    GL_CALL(glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, tex_width, tex_height,
                            GL_LUMINANCE, GL_UNSIGNED_BYTE, y_data));
  }

  GL_CALL(glBindTexture(GL_TEXTURE_2D, u_texture_));
  if (size_changed) {
    GL_CALL(glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, uv_width, uv_height, 0,
                         GL_LUMINANCE, GL_UNSIGNED_BYTE, u_data));
  } else {
    GL_CALL(glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, uv_width, uv_height,
                            GL_LUMINANCE, GL_UNSIGNED_BYTE, u_data));
  }

  GL_CALL(glBindTexture(GL_TEXTURE_2D, v_texture_));
  if (size_changed) {
    GL_CALL(glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, uv_width, uv_height, 0,
                         GL_LUMINANCE, GL_UNSIGNED_BYTE, v_data));
  } else {
    GL_CALL(glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, uv_width, uv_height,
                            GL_LUMINANCE, GL_UNSIGNED_BYTE, v_data));
  }

  GPUPixelContext::GetInstance()->SetActiveGlProgram(filter_program_);
  this->GetFramebuffer()->Activate();

  float imageVertices[]{
      -1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f,
  };

  GL_CALL(glEnableVertexAttribArray(filter_position_attribute_));
  GL_CALL(glVertexAttribPointer(filter_position_attribute_, 2, GL_FLOAT, 0, 0,
                                imageVertices));

  GL_CALL(glEnableVertexAttribArray(filter_tex_coord_attribute_));
  GL_CALL(glVertexAttribPointer(filter_tex_coord_attribute_, 2, GL_FLOAT, 0, 0,
                                GetTextureCoordinate(rotation)));

  GL_CALL(glActiveTexture(GL_TEXTURE0));
  GL_CALL(glBindTexture(GL_TEXTURE_2D, y_texture_));
  filter_program_->SetUniformValue("yTexture", 0);

  GL_CALL(glActiveTexture(GL_TEXTURE1));
  GL_CALL(glBindTexture(GL_TEXTURE_2D, u_texture_));
  filter_program_->SetUniformValue("uTexture", 1);

  GL_CALL(glActiveTexture(GL_TEXTURE2));
  GL_CALL(glBindTexture(GL_TEXTURE_2D, v_texture_));
  filter_program_->SetUniformValue("vTexture", 2);

  glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
  this->GetFramebuffer()->Deactivate();

  Source::DoRender(true);
  return 0;
}

}  // namespace gpupixel
