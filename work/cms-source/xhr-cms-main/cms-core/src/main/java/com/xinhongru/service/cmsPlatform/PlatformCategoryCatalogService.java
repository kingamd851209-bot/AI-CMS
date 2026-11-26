package com.xinhongru.service.cmsPlatform;

import com.xinhongru.entity.PlatformOneshotEntity;
import com.xinhongru.permission.PlatformPermission;
import com.xinhongru.service.cmsPlatform.impl.PlatformOneshotServiceImpl;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 数据中台类别权限目录：把 4 个中心的类别（t_platform_oneshot，按 data_type 区分）汇总为
 * 「按模块分组、含操作选项」的扁平节点列表，供管理员组平台表单渲染类别权限树（前端按 parentId 建树）。
 */
@Service
public class PlatformCategoryCatalogService {
    /** 操作选项（1新增/2编辑/3删除/4审核/5查看） */
    public record OperationOption(Integer id, String title) {}

    /** 类别节点（扁平，前端按 parentId 建树） */
    public record PlatformCategoryNode(
            Integer id, Integer parentId, String label, List<OperationOption> operationOptions) {}

    /** 模块（类型 + 标题 + 该模块类别节点） */
    public record PlatformModuleCategories(
            String type, String label, List<PlatformCategoryNode> categories) {}

    private final PlatformOneshotService oneshotService;

    public PlatformCategoryCatalogService(PlatformOneshotServiceImpl oneshotServiceImpl) {
        this.oneshotService = oneshotServiceImpl;
    }

    public List<PlatformModuleCategories> catalog() {
        List<OperationOption> options = operationOptions();
        List<PlatformModuleCategories> modules = new ArrayList<>();
        modules.add(module(PlatformDataType.PRODUCT, "产品中心", options));
        modules.add(module(PlatformDataType.NEWS, "新闻中心", options));
        modules.add(module(PlatformDataType.SOLUTION, "解决方案", options));
        modules.add(module(PlatformDataType.CASE, "案例中心", options));
        return modules;
    }

    private PlatformModuleCategories module(
            PlatformDataType dataType, String label, List<OperationOption> options) {
        return new PlatformModuleCategories(
                dataType.permissionType(),
                label,
                toNodes(
                        oneshotService.all(dataType),
                        options,
                        PlatformOneshotEntity::getId,
                        PlatformOneshotEntity::getParentId,
                        PlatformOneshotEntity::getClassName));
    }

    private List<OperationOption> operationOptions() {
        List<OperationOption> options = new ArrayList<>();
        for (int i = 0; i < PlatformPermission.OP_TITLES.length; i++) {
            options.add(new OperationOption(i + 1, PlatformPermission.OP_TITLES[i]));
        }
        return options;
    }

    private static List<PlatformCategoryNode> toNodes(
            List<PlatformOneshotEntity> categories,
            List<OperationOption> options,
            Function<PlatformOneshotEntity, Integer> idGetter,
            Function<PlatformOneshotEntity, Integer> parentGetter,
            Function<PlatformOneshotEntity, String> labelGetter) {
        List<PlatformCategoryNode> nodes = new ArrayList<>();
        if (categories == null) {
            return nodes;
        }
        for (PlatformOneshotEntity item : categories) {
            Integer id = idGetter.apply(item);
            if (id == null) {
                continue;
            }
            Integer parentId = parentGetter.apply(item);
            nodes.add(
                    new PlatformCategoryNode(
                            id, parentId == null ? 0 : parentId, labelGetter.apply(item), options));
        }
        return nodes;
    }
}
