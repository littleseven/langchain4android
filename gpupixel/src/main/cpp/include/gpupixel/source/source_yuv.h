/*
 * GPUPixel
 *
 * Created by PixPark on 2021/6/24.
 * Copyright © 2021 PixPark. All rights reserved.
 */

#pragma once

#include <functional>

#include "gpupixel/filter/filter.h"
#include "gpupixel/source/source.h"

namespace gpupixel {
class GPUPixelGLProgram;
class GPUPIXEL_API SourceYUV : public Filter {
 public:
  static std::shared_ptr<SourceYUV> Create();

  ~SourceYUV() override;

  void ProcessData(const uint8_t* y_data,
                   const uint8_t* u_data,
                   const uint8_t* v_data,
                   int width,
                   int height,
                   RotationMode rotation);

  void SetRotation(RotationMode rotation);

  bool Init();

 private:
  SourceYUV();

  int GenerateTextureWithPixels(const uint8_t* y_data,
                                const uint8_t* u_data,
                                const uint8_t* v_data,
                                int width,
                                int height,
                                RotationMode rotation);

 private:
  GPUPixelGLProgram* filter_program_;
  uint32_t filter_position_attribute_;
  uint32_t filter_tex_coord_attribute_;

  uint32_t y_texture_ = 0;
  uint32_t u_texture_ = 0;
  uint32_t v_texture_ = 0;
  RotationMode rotation_ = NoRotation;
  std::shared_ptr<GPUPixelFramebuffer> framebuffer_;
};

}  // namespace gpupixel
