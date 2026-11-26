package com.xinhongru.i18n.task.service;

import com.xinhongru.i18n.task.entity.I18nSiteTaskDetailEntity;
import com.xinhongru.i18n.task.entity.I18nSiteTaskEntity;
import com.xinhongru.i18n.task.view.I18nSiteTaskDetailView;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 多语言站点生成任务：主任务与逐栏目明细、栏目维度的数据同步 */
public interface I18nSiteTaskService {
    /** 同步维度：数据模型 */
    String DIM_MODEL = "model";

    /** 同步维度：模版 */
    String DIM_TEMPLATE = "template";

    /** 同步维度：内容 */
    String DIM_CONTENT = "content";

    /** 内容同步方式：增量（只补目标站点缺失的内容） */
    String CONTENT_MODE_INCREMENT = "increment";

    /** 内容同步方式：覆盖（删除目标站点旧内容后整体复制） */
    String CONTENT_MODE_OVERWRITE = "overwrite";

    /** 翻译方式：原文同步（不翻译） */
    String TRANSLATE_NONE = "none";

    /** 翻译方式：AI 翻译后同步 */
    String TRANSLATE_AI = "ai";

    /** 增量命中已存在内容时的处理：跳过 */
    String EXISTING_SKIP = "skip";

    /** 增量命中已存在内容时的处理：更新 */
    String EXISTING_UPDATE = "update";

    /**
     * 同步选项
     *
     * @param contentMode 内容同步方式，见 CONTENT_MODE_INCREMENT / CONTENT_MODE_OVERWRITE
     * @param contentTranslation 栏目内容翻译方式，见 TRANSLATE_NONE / TRANSLATE_AI
     * @param modelTranslation 数据模型字段业务名称翻译方式，见 TRANSLATE_NONE / TRANSLATE_AI
     * @param existingAction 增量命中已存在内容时的处理，见 EXISTING_SKIP / EXISTING_UPDATE
     */
    record SyncOptions(
            String contentMode,
            String contentTranslation,
            String modelTranslation,
            String existingAction) {
        public static SyncOptions defaults() {
            return new SyncOptions(
                    CONTENT_MODE_OVERWRITE, TRANSLATE_NONE, TRANSLATE_NONE, EXISTING_SKIP);
        }

        /** 增量命中已存在内容时是否更新（否则跳过） */
        public boolean updateExisting() {
            return EXISTING_UPDATE.equals(existingAction);
        }

        public boolean translateContent() {
            return TRANSLATE_AI.equals(contentTranslation);
        }

        public boolean translateModel() {
            return TRANSLATE_AI.equals(modelTranslation);
        }

        public boolean needsTranslation() {
            return translateContent() || translateModel();
        }
    }

    /**
     * 生成站点后创建主任务与明细（每个已复制栏目一条明细）
     *
     * @param task 主任务（含原/目标站点与语言），columnStatus 由调用方给出
     * @param sourceToTargetMenuId 源栏目ID -> 目标栏目ID
     * @return 主任务ID
     */
    Long createTask(I18nSiteTaskEntity task, Map<Integer, Integer> sourceToTargetMenuId);

    /** 主任务列表（关键字匹配原/目标站点名称） */
    List<I18nSiteTaskEntity> listTasks(String keyword);

    /** 主任务详情 */
    I18nSiteTaskEntity getTask(Long taskId);

    /** 任务明细列表 */
    List<I18nSiteTaskDetailEntity> listDetails(Long taskId);

    /** 任务明细视图（补充栏目编码、上级栏目、模版路径，供树形展示与弹窗查看） */
    List<I18nSiteTaskDetailView> listDetailViews(Long taskId);

    /**
     * 按逐条明细各自选定的维度执行同步（会覆盖目标站点对应数据）
     *
     * @param taskId 主任务ID
     * @param detailDimensions 明细ID -> 要同步的维度集合（取值 DIM_MODEL / DIM_TEMPLATE / DIM_CONTENT；
     *     某明细维度为空表示该明细全部维度同步）
     * @param options 同步选项（内容方式、内容/业务名称的翻译方式）；需要翻译时先校验 AI 服务可用
     * @param operatorId 操作人ID
     * @param operatorName 操作人名称
     */
    void syncDetails(
            Long taskId,
            Map<Long, Set<String>> detailDimensions,
            SyncOptions options,
            Long operatorId,
            String operatorName);

    /**
     * 检查并按需启动 AI 翻译服务（同步前调用，便于界面提前提示）。
     *
     * @return null 表示可用，否则为不可用原因
     */
    String ensureTranslationAvailable();

    /**
     * 同步静态资源：把原站点 UIPackage 目录整体复制到目标站点，并回写目标站点 ui_package_version。 后台异步执行，任务 assetSyncStatus 依次为
     * RUNNING -> SUCCESS/FAILED。
     */
    void syncAssets(Long taskId);

    /**
     * 停止该任务正在进行的同步（协作式取消：停在当前栏目/当前文件之后，状态按明细回写）。 若已无在跑线程（如服务重启遗留的 RUNNING），也会直接把状态回写为不再 RUNNING。
     *
     * @return 是否确实处于进行中并已发出停止
     */
    boolean stopSync(Long taskId);
}
