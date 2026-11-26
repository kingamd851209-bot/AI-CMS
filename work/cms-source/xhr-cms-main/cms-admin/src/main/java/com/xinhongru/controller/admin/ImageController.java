package com.xinhongru.controller.admin;

import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.requestDto.cutImageDto.CutImageDto;
import com.xinhongru.requestDto.cutImageDto.CutImagePostDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.site.context.SiteContextService;
import com.xinhongru.utils.ThumbnailsUtils;

import io.swagger.annotations.Api;

import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * @Description @ClassName ImageController @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/4/28
 * 15:23
 */
@Api("图片裁切")
@Slf4j
@Controller
@RequestMapping("/admin/image")
public class ImageController {
    private final ThumbnailsUtils thumbnails;
    private final SiteContextService sites;

    public ImageController(ThumbnailsUtils thumbnails, SiteContextService sites) {
        this.sites = sites;
        this.thumbnails = thumbnails;
    }

    @GetMapping("/cut")
    public String cut(@Validated CutImageDto cutImageDto, Model model) {
        if (StringUtils.isNotBlank(cutImageDto.getImgSrc())
                && cutImageDto.getMinWidth() > 0
                && cutImageDto.getMinHeight() > 0) {
            float zoom = cutImageDto.getZoom();
            float minWidth = (float) Math.ceil(cutImageDto.getMinWidth() / zoom);
            float minHeight = (float) Math.ceil(cutImageDto.getMinHeight() / zoom);
            float maxWidth = cutImageDto.getImageWidth();
            float maxHeight = cutImageDto.getImageHeight();

            float times =
                    maxHeight / minHeight > maxWidth / minWidth
                            ? maxWidth / minWidth
                            : maxHeight / minHeight;

            if (maxHeight / minHeight > maxWidth / minWidth) {
                maxHeight = minHeight * times;
            } else {
                maxWidth = minWidth * times;
            }
            cutImageDto.setMaxWidth(maxWidth);
            cutImageDto.setMaxHeight(maxHeight);
        } else {
            //            //如果是限宽不限高
            //            String InputName = StringDeal.Request.QueryString("InputName");
            //            String imgSrc = StringDeal.Request.QueryString("imgSrc");
            //            String resultSrc = imgSrc;
            //            string tempScript = "$(window.parent.GetFrame('welcome')).find('#" +
            // InputName + "').val('" + resultSrc.Replace("~", "") +
            // "');$(window.parent.GetFrame('welcome')).find('#imgPreview" + InputName +
            // "').attr('src','" + resultSrc.Replace("~", "") +
            // "').show();window.parent.layerClose(window.parent.layer.getFrameIndex(window.name));";
            //            Response.Write("<script src=\"../js/jquery-1.7.1.min.js\"
            // type=\"text/javascript\"></script><script src=\"../js/layer/layer.js\"
            // type=\"text/javascript\"></script><script type=\"text/javascript\">" + tempScript +
            // "</script>");
            //            Response.End();
        }
        model.addAttribute("view", cutImageDto);
        return "redirect:/admin/v2/cms-platform/static-assets";
    }

    @ResponseBody
    @PostMapping("/cut")
    public ResultVO cutImage(
            @RequestBody @Validated CutImagePostDto cutImagePostDto,
            Model model,
            HttpServletRequest request) {
        String originalImagePath = cutImagePostDto.getImgSrc();

        float zoom = cutImagePostDto.getZoom();
        int x = (int) (cutImagePostDto.getX() * zoom);
        int y = (int) (cutImagePostDto.getY() * zoom);
        int width = (int) Math.ceil(cutImagePostDto.getWidth() * zoom);
        int height = (int) Math.ceil(cutImagePostDto.getHeight() * zoom);

        // 文件后缀
        String extension = originalImagePath.substring(originalImagePath.lastIndexOf("."));
        // 压缩后文件名
        String thumbnailPath =
                originalImagePath.replace(extension, width + "_" + height + extension);

        boolean flag =
                thumbnails.thumbnailsImage(
                        originalImagePath,
                        thumbnailPath,
                        x,
                        y,
                        width,
                        height,
                        sites.current(request).getSiteCode());
        if (flag) {
            return ResultVO.success(thumbnailPath);
        } else {
            return ResultVO.fail(ErrorCodeEnum.IMAGE_CUT_FAIL);
        }
    }
}
