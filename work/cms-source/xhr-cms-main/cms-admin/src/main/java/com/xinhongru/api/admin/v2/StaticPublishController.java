package com.xinhongru.api.admin.v2;

import com.xinhongru.api.admin.AdminApiResponse;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.builderHtml.BuilderHtmlVo;
import com.xinhongru.responseVo.builderHtml.NodeVo;
import com.xinhongru.service.oneshot.OneShotService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/admin/v2/static-publish")
public class StaticPublishController {
    @Autowired private OneShotService oneShotService;

    /**
     * 获取栏目树（支持懒加载）
     *
     * @param parentId 父栏目ID，不传则返回根节点
     */
    @GetMapping("/menu-tree")
    public AdminApiResponse<List<NodeVo>> getMenuTree(
            HttpServletRequest request, @RequestParam(required = false) Integer parentId) {
        List<NodeVo> nodes = oneShotService.getBuilderNodes(request, parentId);
        return AdminApiResponse.success(nodes);
    }

    @PostMapping("/create-task")
    public AdminApiResponse<ResultVO> createTask(
            HttpServletRequest request, @RequestBody BuilderHtmlVo builderHtmlVo) {
        ResultVO result = oneShotService.builderHtmlTask(request, builderHtmlVo);
        return AdminApiResponse.success(result);
    }

    @GetMapping("/publish")
    public void publish(
            HttpServletRequest request, HttpServletResponse response, @RequestParam String taskId)
            throws IOException {
        try {
            oneShotService.builderHtml(request, response, taskId);
        } catch (IllegalArgumentException invalid) {
            if (response.isCommitted()) throw invalid;
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(invalid.getMessage());
        }
    }
}
