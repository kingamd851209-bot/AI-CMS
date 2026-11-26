<template>
  <section class="cms-page content-management">

    <!-- 未选择站点提示 -->
    <el-empty v-if="!siteContext.currentSite" description="请先选择站点">
      <el-button type="primary" @click="router.push('/admin/v2')">前往工作台</el-button>
    </el-empty>

    <!-- 加载中 -->
    <div v-else-if="loading && !rootMenus.length" class="content-management__loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>加载中...</span>
    </div>

    <!-- 内容管理布局 -->
    <div
      v-else
      class="content-management__layout"
      :class="{ 'is-tree-collapsed': treeCollapsed }"
      :style="{ '--column-navigation-width': treeWidth == null ? undefined : `${treeWidth}px` }"
    >
      <div v-show="!treeCollapsed" class="content-management__tree-panel">
        <aside v-show="!treeCollapsed" id="content-management-tree" class="cms-card content-management__tree">
          <div class="cms-card__header">
            <div class="content-management__tree-title">
              <h2>栏目导航</h2>
            </div>
            <span class="content-management__count">{{ navigationMenuCount }} 项</span>
          </div>
          <el-input
            v-model="menuFilterText"
            placeholder="搜索栏目"
            clearable
            class="content-management__filter"
          />
          <el-tree
            v-if="rootMenus.length"
            ref="treeRef"
            node-key="id"
            :data="rootMenus"
            :props="treeProps"
            :lazy="true"
            :load="loadChildMenus"
            :filter-node-method="filterMenuNode"
            :default-expanded-keys="expandedKeys"
            :expand-on-click-node="false"
            highlight-current
            @node-click="selectNavigationMenu"
          >
            <template #default="{ data }">
              <span class="content-management__menu-node" :class="{ 'is-denied': !siteContext.hasMenuPermission(Number(data.id)) }">
                <span class="content-management__menu-node-label" :title="data.className">{{ data.className }}</span>
                <el-tag size="small" type="info">{{ data.bizCode }}</el-tag>
                <template v-if="data.id !== siteContext.currentSite?.rootMenuId">
                  <el-tag v-if="data.pageType === 0" size="small" type="info">单篇</el-tag>
                  <el-tag v-else size="small" type="primary">列表</el-tag>
                  <el-tag v-if="!data.hasManageChannel" size="small" type="warning">不可管</el-tag>
                </template>
              </span>
            </template>
          </el-tree>
          <el-empty v-else description="暂无栏目" />
        </aside>
      </div>
      <ColumnNavigationRail
        v-model:collapsed="treeCollapsed"
        v-model:width="treeWidth"
        class="content-management__tree-toggle"
        controls="content-management-tree"
        :breakpoint="960"
      />

      <main v-loading="contentLoading && (!selectedMenu || !languageId)" element-loading-text="正在加载内容…" class="cms-card content-management__main">
        <div class="cms-card__header content-management__heading">
          <div class="content-management__heading-title">
            <template v-if="jumpReturnTarget">
              <span class="content-management__parent-title" :title="jumpParentTitle">上级内容：{{ jumpParentTitle }}</span>
              <el-tag size="small" type="info">{{ jumpXgid }}</el-tag>
              <span aria-hidden="true">/</span>
            </template>
            <h2>{{ selectedMenu?.className || '内容管理' }}</h2>
            <el-tag v-if="selectedMenu" size="small" type="info">{{ selectedMenu.id }}</el-tag>
            <el-tag v-if="selectedMenu" size="small" type="danger">{{ selectedMenu.pageType === 0 ? '单页栏目' : '列表栏目' }}</el-tag>
          </div>
          <div class="cms-page__actions">
            <span v-show="selectedMenu?.pageType === 0" id="content-management-single-status" class="content-management__single-status" />
            <el-button size="small" :loading="loading" @click="loadMenus">刷新</el-button>
            <el-button size="small" v-if="jumpReturnTarget" @click="backToParent">返回上级</el-button>
            <el-button size="small" v-if="selectedMenu && selectedMenu.pageType !== 0 && selectedMenu.hasManageChannel && siteContext.canOperate(selectedMenu.id, 1) && (!groupScope || groupScope.value && groupReady)"
              type="primary" :disabled="!languageId || openingGroupedEditor" @click="openCreate">{{ groupScope ? groupedCreateTitle(groupScope.value) : '新增内容' }}</el-button>
          </div>
        </div>
        <template v-if="selectedMenu">
          <!-- 单页内容 -->
          <template v-if="selectedMenu.pageType === 0">
            <SinglePageEditor ref="singleEditor" :key="`${selectedMenu.id}/${languageId}`"
              v-if="languageId"
              status-target="#content-management-single-status"
              :menu-id="selectedMenu.id"
              :language-id="languageId"
              @saved="onSaved"
            />
            <el-empty v-else description="当前站点未启用内容语言" />
          </template>

          <!-- 列表内容 -->
          <template v-else>
            <div v-if="!selectedMenu.hasManageChannel" class="content-management__readonly">
              <el-alert type="warning" :closable="false" title="当前栏目不可管理" description="此栏目已设置为不可管理，无法新增或编辑内容。" />
            </div>
            <template v-else>
              <section v-if="invalidBatchScope" class="content-management__filters admin-table-toolbar" aria-label="导入批次">
                <el-alert title="导入批次的内容 ID 无效或超过 100 条，请从导入回执重新打开。" type="error" :closable="false" show-icon />
                <el-button size="small" @click="showUngroupedRecords">查看全部未分组记录</el-button>
              </section>
              <section v-if="groupScope && groupReady" class="content-management__filters admin-table-toolbar" :aria-label="`按${groupLabel}维护${groupRowLabel}`">
                <div class="content-management__filters-row">
                  <strong>{{ groupScope.value ? `当前${groupLabel} ${groupScope.value}` : `未分组${groupRowLabel}` }}</strong>
                  <el-select v-model="nextGroup" size="small" filterable allow-create default-first-option :empty-values="[null,undefined]"
                    :aria-label="`切换${groupLabel}`" :placeholder="`选择或输入${groupLabel}`" class="content-management__filter-control" :disabled="openingGroupedEditor || saving">
                    <el-option v-for="group in groupOptions" :key="group.value" :label="`${group.value || '未分组'} · ${group.count} 条${groupRowLabel}`" :value="group.value" />
                  </el-select>
                  <el-button size="small" :disabled="nextGroup === undefined || nextGroup.trim() === groupScope.value || nextGroup.trim().length > 512 || openingGroupedEditor || saving" @click="changeGroup">查看该组</el-button>
                </div>
                <p v-if="groupScope.value">每条{{groupRowLabel}}单独保存。点击顺序号可调整组内顺序。审核后勾选记录并发布。</p>
                <p v-else-if="canAssignGroup">勾选未分组的草稿，再批量移至已核实的{{ groupLabel }}。</p>
                <p v-else>编辑{{groupRowLabel}}并填写所属{{groupLabel}}，记录将归入对应分组。</p>
                <div v-if="importedRecordIds?.size" class="content-management__filters-row">
                  <el-checkbox v-model="onlyImported" :disabled="contentLoading" @change="reloadFirstPage">仅显示本次导入的未分组记录</el-checkbox>
                </div>
                <div v-if="groupAssignmentResult" class="content-management__filters-row" role="status">
                  <span>已将 {{ groupAssignmentResult.updatedCount }} 条草稿移至「{{ groupAssignmentResult.groupValue }}」。</span>
                  <el-button size="small" type="success" plain @click="goToGroup(groupAssignmentResult.groupValue)">打开目标{{ groupLabel }}</el-button>
                </div>
              </section>
              <div v-if="languageId" class="content-management__filters admin-table-toolbar">
                <div class="content-management__filters-row">
                  <span class="content-management__filters-label">筛选条件</span>
                  <div class="content-management__filters-basic">
                    <el-select size="small"
                      v-model="selectedStatuses"
                      multiple
                      clearable
                      collapse-tags
                      placeholder="按状态筛选"
                      class="content-management__filter-control content-management__filter-control--status"
                      @change="loadContent">
                      <el-option label="草稿" value="DRAFT" />
                      <el-option label="待审" value="PENDING_REVIEW" />
                      <el-option label="已驳回" value="REJECTED" />
                      <el-option label="已发布" value="PUBLISHED" />
                      <el-option label="作废" value="ARCHIVED" />
                    </el-select>
                    <el-input size="small" v-model="keyword" placeholder="按标题搜索" clearable class="content-management__filter-control content-management__filter-control--keyword" @keyup.enter="loadContent" />
                    <el-select v-model="querySortField" size="small" clearable placeholder="扩展字段排序" @change="page = 1; loadContent()">
                      <el-option v-for="field in listFields.filter(item => item.fieldName.startsWith('ext_'))" :key="field.fieldName" :label="field.title" :value="field.fieldName" />
                    </el-select>
                    <el-select v-if="querySortField" v-model="querySortDirection" size="small" @change="page = 1; loadContent()">
                      <el-option label="升序（空值最后）" value="asc" /><el-option label="降序（空值最后）" value="desc" />
                    </el-select>
                    <el-button size="small" @click="loadContent">查询</el-button>
                    <el-button size="small" @click="resetSearch">重置</el-button>
                  </div>
                  <el-button size="small"
                    class="content-management__filters-toggle"
                    :aria-expanded="filtersExpanded"
                    aria-controls="content-management-advanced-filters"
                    @click="filtersExpanded = !filtersExpanded"
                  >
                    {{ filtersExpanded ? '收起' : '展开' }}
                    <el-icon><ArrowUp v-if="filtersExpanded" /><ArrowDown v-else /></el-icon>
                  </el-button>
                </div>
                <div
                  v-show="filtersExpanded"
                  id="content-management-advanced-filters"
                  class="content-management__filters-advanced"
                >
                  <template v-for="field in filteredSearchFields" :key="field.fieldName">
                    <el-select v-if="field.fieldName.startsWith('ext_')" v-model="extensionOps[field.fieldName]" size="small" clearable :placeholder="`${field.title}匹配方式`">
                      <el-option label="为空" value="empty" /><el-option label="非空" value="notEmpty" />
                      <template v-if="field.inputMultiple"><el-option label="任一选项" value="any" /><el-option label="全部选项" value="all" /></template>
                      <template v-else-if="field.fieldType === 'text'"><el-option label="包含文字" value="contains" /><el-option label="完全相同" value="eq" /></template>
                      <template v-else-if="field.fieldType === 'number'"><el-option label="等于" value="eq" /><el-option label="大于" value="gt" /><el-option label="大于等于" value="ge" /><el-option label="小于" value="lt" /><el-option label="小于等于" value="le" /></template>
                    </el-select>
                    <template v-if="field.fieldName.startsWith('ext_') && field.fieldType === 'number'">
                      <el-input v-model="numericMin[field.fieldName]" size="small" :placeholder="`${field.title}最小值（含）`" clearable @keyup.enter="loadContent" />
                      <el-input v-model="numericMax[field.fieldName]" size="small" :placeholder="`${field.title}最大值（含）`" clearable @keyup.enter="loadContent" />
                    </template>
                    <el-input size="small"
                      v-if="field.fieldType === 'text' || field.fieldType === 'multiline' || field.fieldType === 'richtext' || field.fieldType === 'number'"
                      v-model="searchValues[field.fieldName]"
                      :placeholder="field.title"
                      clearable
                      class="content-management__filter-control"
                      @keyup.enter="loadContent" />
                    <el-date-picker size="small"
                      v-else-if="field.fieldType === 'datetime'"
                      v-model="dateRanges[field.fieldName]"
                      :type="field.datePrecision === 'DAY' ? 'daterange' : 'datetimerange'"
                      :editable="field.datePrecision !== 'DAY'"
                      :disabled-date="field.datePrecision === 'DAY' ? dayDateDisabled : undefined"
                      :start-placeholder="field.title"
                      end-placeholder="结束"
                      :value-format="field.datePrecision === 'DAY' ? 'YYYY-MM-DD' : 'YYYY-MM-DD HH:mm:ss'"
                      class="content-management__filter-control content-management__filter-control--date"
                      @change="loadContent" />
                    <el-select size="small"
                      v-else-if="field.select"
                      v-model="searchValues[field.fieldName]"
                      :multiple="field.inputMultiple"
                      collapse-tags
                      collapse-tags-tooltip
                      :placeholder="field.title"
                      clearable
                      filterable
                      class="content-management__filter-control"
                      @change="loadContent"
                      @visible-change="(visible: boolean) => { if (visible) ensureSelectOptions(field) }">
                      <el-option v-for="opt in optionsFor(field)" :key="opt.value" :value="opt.value" :label="opt.label" />
                    </el-select>
                    <el-select size="small"
                      v-else
                      v-model="searchValues[field.fieldName]"
                      :multiple="field.inputMultiple"
                      collapse-tags
                      collapse-tags-tooltip
                      :placeholder="field.title"
                      clearable
                      filterable
                      class="content-management__filter-control"
                      @change="loadContent">
                      <el-option v-for="opt in optionsFor(field)" :key="opt.value" :value="opt.value" :label="opt.label" />
                    </el-select>
                  </template>
                  <span v-if="!filteredSearchFields.length" class="content-management__filters-label">暂无更多筛选条件</span>
                </div>
              </div>
              <el-empty v-else description="当前站点未启用内容语言" />
              <ContentListPanel
                v-if="languageId"
                :rows="rows"
                :loading="contentLoading"
                :total="total"
                :page="page"
                :page-size="limit"
                :page-sizes="[20, 50, 100]"
                :pending-review-count="pendingReviewCount"
                :list-fields="listFields"
                :menu-id="selectedMenu?.id ?? 0"
                :jump-columns="jumpColumns"
                :imported-record-ids="importedRecordIds"
                @navigate="gotoSubMenu"
                @edit="openEdit"
                @copy="openCopy"
                @delete="deleteRecord"
                @update:page="page = $event"
                @update:page-size="changePageSize"
                @reload="loadContent"
                @recommend-change="handleRecommendChange"
                @sort-change="handleSortChange"
                @selection-change="selectedRows = $event"
                @seo="openSeo"
                @preview="openPreview"
                @audit="openAudit"
                @translate="openTranslation"
              >
                <template #empty>
                  <span>{{ listEmptyMessage }}</span>
                  <el-button v-if="listEmptyAction === 'reset'" link type="primary" @click="resetSearch">清除筛选</el-button>
                  <el-button v-if="listEmptyAction === 'retry'" link type="primary" @click="loadContent">重试</el-button>
                </template>
              </ContentListPanel>
              <section v-if="languageId" class="content-management__actions" aria-label="批量操作">
                <div class="content-management__selection-state" :class="{ 'has-selection': selectedRows.length > 0 }" aria-live="polite">
                  <strong v-if="selectedRows.length">已选 {{ selectedRows.length }} 条</strong>
                  <span v-else>请选择数据</span>
                </div>
                <div v-if="canAssignGroup" class="content-management__action-group">
                  <el-button size="small" type="primary" :disabled="contentLoading || !canAssignSelection" @click="openGroupAssignment">
                    批量移至{{ groupLabel }}
                  </el-button>
                  <span v-if="selectedRows.length && !canAssignSelection" class="content-management__group-label">请选择可归组的草稿</span>
                </div>
                <div class="content-management__action-group">
                  <!-- <span class="content-management__group-label">发布状态</span> -->
                  <el-button size="small" v-if="siteContext.canOperate(selectedMenu.id, 4)" type="primary" plain :disabled="contentLoading || selectedRows.length === 0" @click="handleBatchOption(25, '存为发布')">存为发布</el-button>
                  <el-button size="small" v-if="siteContext.canOperate(selectedMenu.id, 4)" :disabled="contentLoading || selectedRows.length === 0" @click="handleBatchOption(26, '存为草稿')">存为草稿</el-button>
                </div>
                <div v-if="hasHomeBatchOptions" class="content-management__action-group">
                  <!-- <span class="content-management__group-label">首页展示</span> -->
                  <el-dropdown size="small" trigger="click" @command="handleBatchOptionMenuCommand">
                    <el-button size="small" data-testid="content-batch-home-menu" :disabled="contentLoading || selectedRows.length === 0">首页属性</el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 8)" command="home-top" data-testid="content-batch-home-top">首页置顶</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 8)" command="home-untop">取消首页置顶</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 10)" command="home-recommend">首页推荐</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 10)" command="home-unrecommend">取消首页推荐</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 9)" command="home-hot">首页热门</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 9)" command="home-unhot">取消首页热门</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
                <div v-if="hasMenuBatchOptions" class="content-management__action-group">
                  <!-- <span class="content-management__group-label">栏目展示</span> -->
                  <el-dropdown size="small" trigger="click" @command="handleBatchOptionMenuCommand">
                    <el-button size="small" data-testid="content-batch-menu-menu" :disabled="contentLoading || selectedRows.length === 0">栏目属性</el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 11)" command="channel-top">频道置顶</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 11)" command="channel-untop">取消频道置顶</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 12)" command="channel-hot">频道热门</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 12)" command="channel-unhot">取消频道热门</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 13)" command="channel-recommend">频道推荐</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 13)" command="channel-unrecommend">取消频道推荐</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 14)" command="menu-top">栏目置顶</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 14)" command="menu-untop">取消栏目置顶</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 16)" command="menu-recommend" data-testid="content-batch-menu-recommend">栏目推荐</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 16)" command="menu-unrecommend">取消栏目推荐</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 15)" command="menu-hot">栏目热门</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 15)" command="menu-unhot">取消栏目热门</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 17)" command="nav-recommend">导航推荐</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 17)" command="nav-unrecommend">取消导航推荐</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 18)" command="right-recommend">右侧推荐</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 18)" command="right-unrecommend">取消右侧推荐</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 19)" command="left-recommend">左侧推荐</el-dropdown-item>
                        <el-dropdown-item v-if="siteContext.canOperate(selectedMenu.id, 19)" command="left-unrecommend">取消左侧推荐</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
                <div v-if="siteContext.canOperate(selectedMenu.id, 23)" class="content-management__action-group content-management__action-group--review">
                  <span class="content-management__group-label">审核</span>
                  <el-button size="small" :disabled="contentLoading || selectedRows.length === 0" @click="submitToBpp">提交审核</el-button>
                  <el-button size="small" :disabled="contentLoading || selectedRows.length === 0" @click="openAuditDialog">审核</el-button>
                </div>
                <div v-if="siteContext.canOperate(selectedMenu.id, 3)" class="content-management__action-group content-management__action-group--danger">
                  <!-- <span class="content-management__group-label">删除</span> -->
                  <el-button size="small" type="danger" plain :disabled="contentLoading || selectedRows.length === 0" @click="handleBatchDelete">删除</el-button>
                </div>
              </section>
            </template>
          </template>
        </template>
        <el-empty v-else-if="!contentLoading" description="请从左侧选择栏目" />
      </main>
    </div>

    <!-- 内容编辑抽屉 -->
    <ContentEditorDrawer
      :visible="editorVisible"
      :title="editorTitle"
      :saving="saving"
      native-mode
      :fields="editorFields"
      :list-fields="listFields"
      :content-form="contentForm"
      :menu-id="selectedMenu?.id ?? 0"
      :record-id="editor?.recordId ?? undefined"
      :readonly-field="newRecordGroup?.field"
      @update:visible="editorVisible = $event"
      @save="save"
    >
      <template v-if="editor?.recordId && jumpColumns.length" #owned-content>
        <section aria-label="维护所属条目">
          <strong>维护所属条目</strong>
          <p>在新标签页打开该记录对应的子栏目。</p>
          <el-button v-for="sub in jumpColumns" :key="sub.id" tag="a" target="_blank" rel="noopener"
            :href="subMenuRoute(sub.id, editor.recordId, contentForm.title).href">{{ sub.className }}</el-button>
        </section>
      </template>
    </ContentEditorDrawer>

    <ContentTranslationDrawer
      v-if="translationSource"
      :visible="translationVisible"
      :saving="translationSaving"
      :menu-id="selectedMenu?.id ?? 0"
      :record-id="translationSource.recordId"
      :source-language-id="languageId ?? 0"
      :language-options="allLanguages"
      :fields="translationSource.fields"
      :source-title="translationSource.title"
      :source-values="translationSource.values"
      @update:visible="translationVisible = $event"
      @save="handleTranslationSave"
    />

    <!-- 历史抽屉 -->
    <el-drawer v-model="historyVisible" title="版本与审计历史" size="640px">
      <h3>版本历史</h3>
      <el-empty v-if="!legacyHistory.histories.length" description="暂无版本" />
      <el-timeline v-else-if="legacyHistory.histories.length">
        <el-timeline-item v-for="item in legacyHistory.histories" :key="item.oldContentId" :timestamp="item.operationTime">
          <strong>{{ item.title }}</strong>
          <p>操作人：{{ item.operatorName }} ({{ item.operatorAccount }})</p>
          <p>状态：{{ item.statusText }}</p>
          <p v-if="item.updateDescription">{{ item.updateDescription }}</p>
          <el-collapse v-if="item.fields?.length">
            <el-collapse-item :title="`查看字段详情（${item.fields.length} 个）`" :name="`history-${item.oldContentId}`">
              <el-table :data="item.fields" size="small" border>
                <el-table-column label="字段" min-width="180">
                  <template #default="{ row }">
                    <span class="history-field__key">{{ row.fieldKey }}</span>
                    <span v-if="row.fieldName && row.fieldName !== row.fieldKey" class="history-field__title">（{{ row.fieldName }}）</span>
                  </template>
                </el-table-column>
                <el-table-column label="修改前" min-width="220">
                  <template #default="{ row }">
                    <template v-if="row.updateBefore ?? null !== null">
                      <span class="history-field__text">{{ row.updateBefore }}</span>
                      <div v-if="parsePreviewUrls(row.updateBefore).length" class="history-field__preview">
                        <ResourcePreview v-for="url in parsePreviewUrls(row.updateBefore)" :key="url" :url="url" />
                      </div>
                    </template>
                    <span v-else class="history-field__empty">（空）</span>
                  </template>
                </el-table-column>
                <el-table-column label="修改后" min-width="220">
                  <template #default="{ row }">
                    <template v-if="row.updateAfter ?? null !== null">
                      <span class="history-field__text">{{ row.updateAfter }}</span>
                      <div v-if="parsePreviewUrls(row.updateAfter).length" class="history-field__preview">
                        <ResourcePreview v-for="url in parsePreviewUrls(row.updateAfter)" :key="url" :url="url" />
                      </div>
                    </template>
                    <span v-else class="history-field__empty">（空）</span>
                  </template>
                </el-table-column>
              </el-table>
            </el-collapse-item>
          </el-collapse>
          <div style="margin-top: 6px">
            <el-button size="small" type="primary" plain :loading="historyRolling" @click="rollbackLegacy(item)">
              回滚到此版本
            </el-button>
          </div>
        </el-timeline-item>
      </el-timeline>
      <section>
        <h3>审计轨迹</h3>
        <el-empty v-if="!legacyHistory.auditTrail.length" description="暂无审计记录" />
        <el-timeline v-else-if="legacyHistory.auditTrail.length">
          <el-timeline-item v-for="item in legacyHistory.auditTrail" :key="item.id" :timestamp="item.submitTime">
            <strong>{{ item.contentTitle }}</strong>
            <p>提交人：{{ item.submitterName }}</p>
            <p v-if="item.submitDescription">提交说明：{{ item.submitDescription }}</p>
            <p v-if="item.checkerName">审核人：{{ item.checkerName }}</p>
            <p v-if="item.checkTime">审核时间：{{ item.checkTime }}</p>
            <p v-if="item.checkDescription">审核说明：{{ item.checkDescription }}</p>
            <p>状态：{{ item.statusText }}</p>
          </el-timeline-item>
        </el-timeline>
      </section>
    </el-drawer>

    <!-- SEO 设置抽屉 -->
    <el-drawer v-model="seoDrawerVisible" title="SEO 设置" size="500px">
      <div class="seo-drawer__ai">
        <el-button type="primary" plain data-testid="seo-ai-generate-button" @click="onSeoAiGenerate">
          AI 生成
        </el-button>
        <span class="seo-drawer__ai-hint">AI 自动生成 SEO 标题、关键词与描述</span>
      </div>
      <el-form :model="seoForm" label-width="100px">
        <el-form-item label="SEO 标题">
          <el-input v-model="seoForm.seoTitle" placeholder="搜索引擎显示的标题" />
        </el-form-item>
        <el-form-item label="SEO 关键词">
          <el-input v-model="seoForm.seoKeywords" placeholder="关键词，用逗号分隔" />
        </el-form-item>
        <el-form-item label="SEO 描述">
          <el-input v-model="seoForm.seoDescription" type="textarea" :rows="3" placeholder="搜索引擎显示的描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="seoDrawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="seoSaving" @click="saveSeo">保存</el-button>
      </template>
    </el-drawer>

    <!-- 审核对话框 -->
    <el-dialog v-model="auditDialogVisible" title="审核内容" width="500px">
      <el-form :model="auditForm" label-width="100px">
        <el-form-item label="审核状态">
          <el-select v-model="auditForm.checkStatus" placeholder="请选择审核状态">
            <el-option label="待审批" :value="0" />
            <el-option label="通过" :value="1" />
            <el-option label="未通过" :value="2" />
            <el-option label="作废" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="审核备注">
          <el-input v-model="auditForm.checkDescription" type="textarea" :rows="3" placeholder="请输入审核备注" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="auditDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="auditSubmitting" @click="submitAudit">提交</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="groupAssignmentVisible" :title="'批量移至' + groupLabel" width="min(520px, 92vw)"
      :close-on-click-modal="false" :close-on-press-escape="!groupAssigning" :show-close="!groupAssigning">
      <p>此操作会为已选的 {{ groupAssignmentRecords.length }} 条记录填写同一个{{ groupLabel }}。这些记录仍是草稿，未选记录保留原分组。</p>
      <el-form label-position="top">
        <el-form-item :label="'目标' + groupLabel">
          <el-select v-model="targetGroupValue" filterable allow-create :aria-label="'目标' + groupLabel"
            :placeholder="'选择或输入已核实的' + groupLabel" :disabled="groupAssigning">
            <el-option v-for="group in assignmentOptions" :key="group.value" :label="group.value" :value="group.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <p v-if="targetGroupValue.trim().length > 512" role="alert">目标{{ groupLabel }}最多 512 个字符。</p>
      <el-alert v-if="groupAssignmentError" :title="groupAssignmentError" type="error" :closable="false" show-icon />
      <template #footer>
        <el-button :disabled="groupAssigning" @click="groupAssignmentVisible = false">取消</el-button>
        <el-button v-if="groupAssignmentConflict" :disabled="groupAssigning" @click="refreshGroupAssignment">刷新列表</el-button>
        <el-button type="primary" :loading="groupAssigning"
          :disabled="!targetGroupValue.trim() || targetGroupValue.trim().length > 512 || groupAssignmentConflict"
          @click="submitGroupAssignment">确认移动 {{ groupAssignmentRecords.length }} 条草稿</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { createTableLoader } from '@/utils/tableLoader';
import { useResourceReferences } from '@/api/resourceReferences';
import ColumnNavigationRail from '@/components/common/ColumnNavigationRail.vue';
import { buildAuthorizedNavigation } from './contentNavigation';
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowDown, ArrowUp, Loading } from '@element-plus/icons-vue';
import ResourcePreview from '@/components/resources/ResourcePreview.vue';
import ContentEditorDrawer from './content/ContentEditorDrawer.vue';
import ContentListPanel from './content/ContentListPanel.vue';
import ContentTranslationDrawer from './content/ContentTranslationDrawer.vue';
import SinglePageEditor from './content/SinglePageEditor.vue';
import { listLanguages } from '@/api/languages';
import {
  assignNativeContentGroup,
  auditContentV2,
  type BatchOperationResponse,
  fetchNativeSelectOptions,
  listNativeContent,
  listNativeContentGroups,
  listNativeLocales,
  loadNativeEditor,
  saveNativeDraft,
  toContentEditorField,
  updateNativeContentSort,
  type ListFieldConfig,
  type NativeContentGroup,
  type NativeContentEditorDetail,
  type NativeContentRow,
  type NativeFieldDefinition,
  type NativeLocale,
  type NativeSearchField,
  type NativeWorkflowStatus,
  type SelectOptionItem
} from '@/api/cms';
import { AdminHttpError, http } from '@/api/http';
import { getUiPackageAccessUrl, startUiPackage } from '@/api/uiPackage';
import { useSiteContextStore } from '@/stores/siteContext';
import { packMultiValue } from '@/utils/multiValue';

interface MenuNode {
  id: number;
  bizCode?: string;
  parentId: number;
  className: string;
  pageType?: number;
  hasManageChannel?: boolean;
  hasChildren?: boolean;
  children?: MenuNode[];
  isSubMenu?: boolean;
  parentListFilterField?: string;
}

const { resolve: resolveResourceReference } = useResourceReferences();
const siteContext = useSiteContextStore();
siteContext.$onAction(({ name, args }) => {
  if (name === 'switchSite' && String(args[0]) !== String(siteContext.currentSite?.id) && (singleEditor.value?.dirty || singleEditor.value?.writing)) {
    const message = '请先保存或放弃单页修改，再切换站点。';
    ElMessage.warning(message); throw new Error(message);
  }
});
const router = useRouter();
const singleEditor = ref<InstanceType<typeof SinglePageEditor>>();
const confirmSingleLeave = () => singleEditor.value?.confirmLeave() ?? true;
onBeforeRouteLeave(confirmSingleLeave);
onBeforeRouteUpdate((to, from) => to.fullPath === from.fullPath || confirmSingleLeave());
const treeCollapsed = ref(false);
const treeWidth = ref<number>();
const filtersExpanded = ref(false);
const loading = ref(false);
const contentLoading = ref(false);
const saving = ref(false);
const menuFilterText = ref('');
const treeRef = ref<{ filter: (val: string) => void; setCurrentKey: (key: number | string | null) => void } | null>(null);
const flatMenus = ref<MenuNode[]>([]);
const rootMenus = ref<MenuNode[]>([]);
const navigation = computed(() => buildAuthorizedNavigation(
  flatMenus.value, siteContext.hasMenuPermission, siteContext.currentSite?.rootMenuId ?? undefined
));
const navigationMenuCount = computed(() => navigation.value.count);
let menuLoadVersion = 0;
const expandedKeys = ref<number[]>([]);
const selectedMenu = ref<MenuNode | null>(null);

const route = useRoute();
const genericGroup = computed(() => route.query.groupDisplay === 'records');
const groupLabel = computed(() => genericGroup.value ? '分组' : '年份');
const groupRowLabel = computed(() => genericGroup.value ? '记录' : '事件');
const groupOptions = ref<NativeContentGroup[]>([]);
const groupReady = ref(false);
const nextGroup = ref<string>();
const newRecordGroup = ref<{field:string;value:string}>();
const openingGroupedEditor = ref(false);
const groupScope = computed(() => {
  const {siteId, menuId, groupField, groupValue, xgid} = route.query;
  const menu = selectedMenu.value;
  if (xgid || !menu?.hasManageChannel || menu.pageType !== 1 || !siteContext.hasMenuPermission(menu.id) ||
      siteId !== String(siteContext.currentSite?.id) || menuId !== String(menu.id) ||
      typeof groupField !== 'string' || !groupField || typeof groupValue !== 'string') return;
  return {field:groupField, value:groupValue.trim()};
});
const importedBatchScope = computed(() => {
  const seedIds = route.query.seedIds;
  if (groupScope.value?.value !== '' || seedIds === undefined) return;
  if (typeof seedIds !== 'string') return null;
  const ids = seedIds.split(',');
  if (ids.length > 100 || ids.some(id => !/^[1-9]\d{0,9}$/.test(id) || Number(id) > 2147483647)) return null;
  return new Set(ids.map(Number));
});
const invalidBatchScope = computed(() => importedBatchScope.value === null);
const importedRecordIds = computed(() => importedBatchScope.value ?? undefined);
const onlyImported = ref(true);
const canAssignGroup = computed(() => groupReady.value && groupScope.value?.value === '' &&
  !!selectedMenu.value && siteContext.canOperate(selectedMenu.value.id, 2));
const assignmentOptions = computed(() => groupOptions.value.filter(group => !!group.value));
const groupAssignmentVisible = ref(false);
const groupAssignmentRecords = ref<Array<{ recordId: number; rowVersion: string }>>([]);
const targetGroupValue = ref('');
const groupAssignmentError = ref('');
const groupAssignmentConflict = ref(false);
const groupAssigning = ref(false);
const groupAssignmentResult = ref<{ groupValue: string; updatedCount: number }>();

function groupedCreateTitle(value: string) {
  return genericGroup.value ? `为「${value}」新增记录` : `为 ${value} 年新增事件`;
}

function changeGroup() {
  if (!groupScope.value || nextGroup.value === undefined) return;
  goToGroup(nextGroup.value.trim());
}

function goToGroup(groupValue: string) {
  const query = { ...route.query };
  if (groupValue) delete query.seedIds;
  void router.push({ path: route.path, query: { ...query, groupValue } });
}

function showUngroupedRecords() {
  const query = { ...route.query };
  delete query.seedIds;
  void router.replace({ path: route.path, query });
}

/** 父栏目跳转带来的归属筛选值（t_contents.id）；为空表示不筛选 */
const jumpXgid = ref('');

/** 当前栏目的跳转列：本栏目下开启「父栏目跳转列」的子栏目 */
const jumpColumns = computed(() =>
  flatMenus.value
    .filter(m => Number(m.parentId) === Number(selectedMenu.value?.id) && m.isSubMenu === true)
    .map(m => ({ id: m.id, className: m.className.replace(/┃\s*/g, '').trim() }))
);

/** 点击跳转列：切到子栏目，并按该行内容 id 过滤 */
function gotoSubMenu(payload: { sub: { id: number; className: string }; row: NativeContentRow }) {
  void router.push(subMenuRoute(payload.sub.id, payload.row.recordId, payload.row.title).fullPath);
}
function subMenuRoute(menuId: number, recordId: number, title?: string) {
  return router.resolve({
    path: route.path,
    query: {
      menuId: String(menuId),
      xgid: String(recordId),
      parentTitle: title?.trim() || ''
    }
  });
}

/** 从路由 query 恢复：指定栏目 + 预置归属筛选 */
async function applyRouteQuery() {
  const qMenuId = Number(route.query.menuId);
  jumpXgid.value = typeof route.query.xgid === 'string' ? route.query.xgid : '';
  if (!Number.isFinite(qMenuId) || qMenuId <= 0) {
    return;
  }
  const target = flatMenus.value.find(m => Number(m.id) === qMenuId)
    ?? rootMenus.value.find(m => Number(m.id) === qMenuId);
  if (target) {
    await selectMenu(target);
  }
}

/** 从跳转列进入的二级栏目：返回上级时回到父栏目列表 */
const jumpReturnTarget = computed<number | null>(() => {
  if (!jumpXgid.value) return null;
  const parentId = Number(selectedMenu.value?.parentId ?? 0);
  return parentId > 0 ? parentId : null;
});

const jumpParentTitle = computed(() =>
  (typeof route.query.parentTitle === 'string' ? route.query.parentTitle.trim() : '') || '未命名内容'
);

function backToParent() {
  if (jumpReturnTarget.value == null) return;
  void router.push({ path: route.path, query: { menuId: String(jumpReturnTarget.value) } });
}
const locales = ref<NativeLocale[]>([]);
const languageId = ref<number>();
const rows = ref<NativeContentRow[]>([]);
const listFields = ref<ListFieldConfig[]>([]);
const searchFields = ref<NativeSearchField[]>([]);
const searchValues = reactive<Record<string, string | string[]>>({});
const extensionOps = reactive<Record<string, string>>({});
const numericMin = reactive<Record<string, string>>({});
const numericMax = reactive<Record<string, string>>({});
const querySortField = ref('');
const querySortDirection = ref('asc');
const dateRanges = reactive<Record<string, [string, string] | null>>({});
const selectOptionsCache = ref<Record<string, SelectOptionItem[]>>({});
const total = ref(0);
const page = ref(1);
const limit = ref(20);
const keyword = ref('');
const selectedStatuses = ref<NativeWorkflowStatus[]>([]);
const selectedRows = ref<NativeContentRow[]>([]);
const canAssignSelection = computed(() => selectedRows.value.length > 0 && selectedRows.value.length <= 100 &&
  selectedRows.value.every(row => row.workflowStatus === 'DRAFT' && !!row.rowVersion));
const listEmptyMessage = ref('暂无内容');
const listEmptyAction = ref<'reset' | 'retry' | ''>('');
const contentLoader = createTableLoader(contentLoading);
let selectionVersion = 0;

function clearContent() {
  rows.value = [];
  total.value = 0;
  selectedRows.value = [];
}

const editorVisible = ref(false);
const historyVisible = ref(false);
const historyRolling = ref(false);
const seoDrawerVisible = ref(false);
const seoForm = reactive({ seoTitle: '', seoKeywords: '', seoDescription: '', detailPageUrl: '' });
const seoSaving = ref(false);
const seoRow = ref<NativeContentRow | null>(null);
const auditDialogVisible = ref(false);
const auditSubmitting = ref(false);
const auditForm = reactive({ checkStatus: 0, checkDescription: '' });
const editor = ref<NativeContentEditorDetail>();
const translationVisible = ref(false);
const translationSaving = ref(false);
const translationSource = ref<{
  recordId: number;
  title: string;
  values: Record<string, unknown>;
  fields: Array<ReturnType<typeof toContentEditorField>>;
} | null>(null);
// 目标语言下拉取自「系统设置-语言配置」的全局语言表（与「新建多语言站点」一致），
// 展示完整语言列表（不排除当前语言）。
const allLanguages = ref<Array<{ languageId: number; languageCode: string; languageName: string }>>([]);
async function ensureTranslationLanguages() {
  if (allLanguages.value.length) return;
  try {
    const items = await listLanguages();
    allLanguages.value = items.map((item) => ({
      languageId: item.id,
      languageCode: item.languageCode,
      languageName: item.languageName
    }));
  } catch {
    allLanguages.value = [];
    ElMessage.error('语言列表加载失败');
  }
}
const legacyHistory = ref<{ histories: Array<{ oldContentId: number; contentId: number; title: string; operatorName: string; operatorAccount: string; operationTime: string; status: number; statusText: string; diffSummary: string; updateDescription: string; fields: Array<{ fieldKey: string; fieldName: string; updateBefore: unknown; updateAfter: unknown }> }>; auditTrail: Array<{ id: number; contentId: number; contentTitle: string; submitterName: string; submitDescription: string; submitTime: string; checkerName: string; checkDescription: string; checkTime: string; status: number; statusText: string }> }>({ histories: [], auditTrail: [] });
const contentForm = reactive({
  title: '',
  sort: 0,
  seoTitle: '',
  seoKeywords: '',
  seoDescription: '',
  detailPageUrl: '',
  indexTop: false,
  indexElite: false,
  menuTop: false,
  menuElite: false,
  xgid: null as number | null,
  values: {} as Record<string, string | number | boolean | null>,
  relations: {} as Record<string, string>
});

const treeProps = {
  label: 'className',
  children: 'children',
  isLeaf: (data: MenuNode) => !data.hasChildren
};

const editorFields = computed(() => (editor.value?.fields ?? []).map(toContentEditorField));
const editorTitle = computed(() => newRecordGroup.value
  ? groupedCreateTitle(newRecordGroup.value.value) : editor.value?.recordId ? '编辑内容' : '新增内容');
const pendingReviewCount = computed(() => rows.value.filter((row) => row.workflowStatus === 'PENDING_REVIEW').length);
const hasHomeBatchOptions = computed(() => {
  const menuId = selectedMenu.value?.id;
  return menuId != null && [8, 9, 10].some((optionId) => siteContext.canOperate(menuId, optionId));
});
const hasMenuBatchOptions = computed(() => {
  const menuId = selectedMenu.value?.id;
  return menuId != null && [11, 12, 13, 14, 15, 16, 17, 18, 19].some((optionId) => siteContext.canOperate(menuId, optionId));
});
// 标题已由 keyword 输入框单独处理，动态搜索字段排除 title 避免重复
const filteredSearchFields = computed(() => searchFields.value.filter((f) => f.fieldName !== 'title' && f.fieldName !== groupScope.value?.field));

const batchOptionMenuActions: Record<string, { optionType: number; label: string }> = {
  'home-top': { optionType: 1, label: '首页置顶' },
  'home-untop': { optionType: 2, label: '取消首页置顶' },
  'home-recommend': { optionType: 5, label: '首页推荐' },
  'home-unrecommend': { optionType: 6, label: '取消首页推荐' },
  'home-hot': { optionType: 3, label: '首页热门' },
  'home-unhot': { optionType: 4, label: '取消首页热门' },
  'channel-top': { optionType: 7, label: '频道置顶' },
  'channel-untop': { optionType: 8, label: '取消频道置顶' },
  'channel-hot': { optionType: 9, label: '频道热门' },
  'channel-unhot': { optionType: 10, label: '取消频道热门' },
  'channel-recommend': { optionType: 11, label: '频道推荐' },
  'channel-unrecommend': { optionType: 12, label: '取消频道推荐' },
  'nav-recommend': { optionType: 19, label: '导航推荐' },
  'nav-unrecommend': { optionType: 20, label: '取消导航推荐' },
  'right-recommend': { optionType: 21, label: '右侧推荐' },
  'right-unrecommend': { optionType: 22, label: '取消右侧推荐' },
  'left-recommend': { optionType: 23, label: '左侧推荐' },
  'left-unrecommend': { optionType: 24, label: '取消左侧推荐' },
  'menu-top': { optionType: 13, label: '栏目置顶' },
  'menu-untop': { optionType: 14, label: '取消栏目置顶' },
  'menu-recommend': { optionType: 17, label: '栏目推荐' },
  'menu-unrecommend': { optionType: 18, label: '取消栏目推荐' },
  'menu-hot': { optionType: 15, label: '栏目热门' },
  'menu-unhot': { optionType: 16, label: '取消栏目热门' }
};

watch(menuFilterText, (val) => {
  treeRef.value?.filter(val);
});

// 监听站点切换，重新加载数据
watch(
  () => siteContext.currentSite?.id,
  (newSiteId, oldSiteId) => {
    if (newSiteId !== oldSiteId) {
      selectionVersion++;
      clearGroupedEditor();
      contentLoader.invalidate();
      clearContent();
      querySortField.value = '';
      for (const key of Object.keys(extensionOps)) delete extensionOps[key];
      for (const key of Object.keys(numericMin)) delete numericMin[key];
      for (const key of Object.keys(numericMax)) delete numericMax[key];
      listFields.value = [];
      searchFields.value = [];
      // 清空当前选中状态
      selectedMenu.value = null;
      rootMenus.value = [];
      flatMenus.value = [];
      rows.value = [];
      total.value = 0;
      locales.value = [];
      languageId.value = undefined;
      // 重新加载栏目数据
      void loadMenus();
    }
  }
);

onMounted(async () => {
  await loadMenus();
  await applyRouteQuery();
});

// 同一页面内二次跳转（点另一行的「进入」）时，query 变化需要重新加载
watch(() => route.fullPath, () => { void applyRouteQuery(); });

watch(navigation, (value) => {
  rootMenus.value = value.roots;
  // 站点根节点仅作为容器，默认同时展开其下的一级栏目。
  expandedKeys.value = value.roots.flatMap(menu =>
    menu.id === siteContext.currentSite?.rootMenuId
      ? [menu.id, ...(value.childrenById.get(menu.id) ?? []).map(child => child.id)]
      : [menu.id]
  );
});

async function loadMenus() {
  const version = ++menuLoadVersion;
  const currentSiteInfo = siteContext.currentSite;
  if (!currentSiteInfo) {
    flatMenus.value = [];
    loading.value = false;
    return;
  }

  loading.value = true;
  try {
    const fullData = await http.get<{ flat: MenuNode[] }>('/api/admin/v2/cms/menus');
    if (version !== menuLoadVersion || currentSiteInfo.id !== siteContext.currentSite?.id) return;
    flatMenus.value = fullData.flat ?? [];
    const first = navigation.value.firstAuthorized;
    if (!selectedMenu.value && first && !route.query.menuId) {
      await selectMenu(first);
    }
  } catch (error) {
    if (version === menuLoadVersion) ElMessage.error(errorMessage(error, '栏目加载失败'));
  } finally {
    if (version === menuLoadVersion) loading.value = false;
  }
}

function loadChildMenus(node: { level: number; data: MenuNode }, resolve: (data: MenuNode[]) => void) {
  resolve(node.level === 0 ? navigation.value.roots : navigation.value.childrenById.get(node.data.id) ?? []);
}

function filterMenuNode(value: string, data: MenuNode) {
  if (!value) return true;
  return data.className.includes(value);
}

async function selectNavigationMenu(menu: MenuNode) {
  if (!siteContext.hasMenuPermission(Number(menu.id)) || !(await confirmSingleLeave())) {
    treeRef.value?.setCurrentKey(selectedMenu.value?.id ?? null);
    return;
  }

  // 左侧导航进入普通栏目，清除二级栏目跳转参数，避免刷新或后续查询恢复旧的归属条件。
  const query = { ...route.query };
  delete query.menuId;
  delete query.xgid;
  delete query.parentTitle;
  delete query.groupField;
  delete query.groupValue;
  delete query.groupDisplay;
  delete query.seedIds;
  if ('groupField' in route.query) delete query.siteId;
  if ('menuId' in route.query || 'xgid' in route.query || 'parentTitle' in route.query || 'groupField' in route.query) {
    const failure = await router.replace({ path: route.path, query });
    if (failure) return;
  }

  jumpXgid.value = '';
  await selectMenu(menu);
}

async function selectMenu(menu: MenuNode) {
  // 无权限栏目置灰可见但不可操作
  if (!siteContext.hasMenuPermission(Number(menu.id))) {
    return;
  }
  if (!(await confirmSingleLeave())) return;
  const version = ++selectionVersion;
  clearGroupedEditor();
  contentLoader.invalidate();
  clearContent();
  querySortField.value = '';
  for (const key of Object.keys(extensionOps)) delete extensionOps[key];
  for (const key of Object.keys(numericMin)) delete numericMin[key];
  for (const key of Object.keys(numericMax)) delete numericMax[key];
  listFields.value = [];
  searchFields.value = [];
  selectOptionsCache.value = {};
  selectedMenu.value = null;
  contentLoading.value = true;
  let contentStarted = false;
  try {
    const detail = await http.get<MenuNode>(`/api/admin/v2/cms/menus/${menu.id}`);
    if (version !== selectionVersion) return;
    selectedMenu.value = detail;
    page.value = 1;
    keyword.value = '';

    // 加载语言设置（单页和列表都需要）
    if (!languageId.value) {
      await loadLocales();
      if (version !== selectionVersion) return;
    }

    // 列表栏目加载内容
    if (detail.pageType !== 0 && detail.hasManageChannel) {
      contentStarted = true;
      await loadContent();
    }
  } catch (error) {
    if (version === selectionVersion) ElMessage.error(errorMessage(error, '栏目加载失败'));
  } finally {
    if (version === selectionVersion && !contentStarted) contentLoading.value = false;
  }
}

async function loadLocales() {
  const siteId = siteContext.currentSite?.id;
  if (!siteId) return;
  try {
    const data = await listNativeLocales(siteId);
    if (siteId !== siteContext.currentSite?.id) return;
    locales.value = data;
    if (!languageId.value) {
      languageId.value = locales.value.find(l => l.defaultLanguage)?.languageId ?? locales.value[0]?.languageId;
    }
  } catch (error) {
    if (siteId !== siteContext.currentSite?.id) return;
    ElMessage.error('语言列表加载失败');
  }
}

async function loadContent() {
  const request = contentLoader.begin(clearContent);
  const group = groupScope.value;
  const batchIds = onlyImported.value ? importedRecordIds.value : undefined;
  try {
    if (!selectedMenu.value || !languageId.value || invalidBatchScope.value) return;
    const search = buildFilters();
    const filtered = Boolean(keyword.value.trim() || selectedStatuses.value.length ||
      Object.keys(search).some(key => !['sortField', 'sortDirection'].includes(key)));
    const data = await listNativeContent({
      menuId: selectedMenu.value.id,
      languageId: languageId.value,
      statuses: selectedStatuses.value,
      keyword: keyword.value.trim(),
      page: page.value,
      limit: limit.value,
      filters: { ...search, ...(jumpXgid.value ? { xgid: jumpXgid.value } : {}),
        ...(group ? {groupField:group.field, groupValue:group.value} : {}),
        ...(batchIds?.size ? { seedIds: [...batchIds].join(',') } : {}) }
    });
    if (!request.isCurrent()) return;
    listEmptyAction.value = filtered ? 'reset' : '';
    listEmptyMessage.value = filtered ? '没有符合筛选条件的内容' : group || batchIds?.size || jumpXgid.value ? '当前维护范围暂无记录' : '当前栏目在所选语言下暂无内容';
    rows.value = data.rows;
    total.value = data.total;
    listFields.value = data.listFields || [];
    searchFields.value = data.searchFields || [];
    initSearchFields(searchFields.value);
    if (group) {
      groupReady.value = true;
      const menuId = selectedMenu.value.id;
      void listNativeContentGroups(menuId, group.field).then(values => {
        if (request.isCurrent()) groupOptions.value = values;
      }).catch(() => {});
    }
  } catch (error) {
    if (!request.isCurrent()) return;
    listEmptyAction.value = 'retry';
    listEmptyMessage.value = '内容读取失败';
    ElMessage.error('内容列表加载失败');
    rows.value = [];
    total.value = 0;
    if (group) groupReady.value = false;
  } finally {
    request.finish();
  }
}

function buildFilters(): Record<string, string> {
  const filters: Record<string, string> = {};
  if (querySortField.value) { filters.sortField = querySortField.value; filters.sortDirection = querySortDirection.value; }
  for (const field of filteredSearchFields.value) {
    const op = extensionOps[field.fieldName];
    if (op) {
      filters[`op_${field.fieldName}`] = op;
      if (op === 'empty' || op === 'notEmpty') continue;
    }
    if (field.fieldName.startsWith('ext_') && field.fieldType === 'number') {
      if (numericMin[field.fieldName]) filters[`min_${field.fieldName}`] = numericMin[field.fieldName];
      if (numericMax[field.fieldName]) filters[`max_${field.fieldName}`] = numericMax[field.fieldName];
    }
    if (field.fieldType === 'datetime') {
      const range = dateRanges[field.fieldName];
      if (range?.[0]) filters[`startTime_${field.fieldName}`] = range[0];
      if (range?.[1]) filters[`endTime_${field.fieldName}`] = range[1];
    } else {
      const raw = searchValues[field.fieldName];
      if (Array.isArray(raw)) {
        // 多选字段打包为 ,a,b,，后端按「命中任一」匹配
        if (raw.length) filters[field.fieldName] = packMultiValue(raw);
      } else {
        const value = field.fieldName.startsWith('ext_') && (field.fieldType === 'text' || field.fieldType === 'number') ? raw : raw?.trim();
        if (value) filters[field.fieldName] = value;
      }
    }
  }
  return filters;
}

function dayDateDisabled(value: Date) {
  return value.getFullYear() < 100 || value.getFullYear() > 9999;
}

function changePageSize(size: number) {
  limit.value = size;
  page.value = 1;
  void loadContent();
}

function resetSearch() {
  querySortField.value = ''; querySortDirection.value = 'asc';
  for (const key of Object.keys(extensionOps)) delete extensionOps[key];
  for (const key of Object.keys(numericMin)) delete numericMin[key];
  for (const key of Object.keys(numericMax)) delete numericMax[key];
  for (const field of filteredSearchFields.value) {
    if (field.inputMultiple) {
      searchValues[field.fieldName] = [];
    } else {
      delete searchValues[field.fieldName];
    }
    delete dateRanges[field.fieldName];
    dateRanges[field.fieldName] = null;
  }
  keyword.value = '';
  selectedStatuses.value = [];
  page.value = 1;
  void loadContent();
}

function optionsFor(field: NativeSearchField): SelectOptionItem[] {
  if (field.select) {
    return selectOptionsCache.value[field.fieldName] || [];
  }
  return (field.options || []).map((opt) => ({ value: opt, label: opt }));
}

function ensureSelectOptions(field: NativeSearchField) {
  if (!field.select || !field.selectQuery) return;
  if (selectOptionsCache.value[field.fieldName] != null) return;
  if (!selectedMenu.value) return;
  const menuId = selectedMenu.value.id;
  const version = selectionVersion;
  fetchNativeSelectOptions(menuId, field.selectQuery)
    .then((opts) => {
      if (version !== selectionVersion || selectedMenu.value?.id !== menuId) return;
      selectOptionsCache.value[field.fieldName] = opts || [];
    })
    .catch(() => {
      if (version !== selectionVersion || selectedMenu.value?.id !== menuId) return;
      delete selectOptionsCache.value[field.fieldName];
    });
}

function initSearchFields(fields: NativeSearchField[]) {
  for (const field of fields) {
    // 多选下拉绑定数组，预置空数组避免初始 undefined
    if (field.inputMultiple && searchValues[field.fieldName] === undefined) {
      searchValues[field.fieldName] = [];
    }
    if (field.select) ensureSelectOptions(field);
  }
}

function onSaved() {
  // 单页保存后刷新
}

async function openCreate() {
  if (!selectedMenu.value || !languageId.value || !siteContext.canOperate(selectedMenu.value.id, 1) || openingGroupedEditor.value) return;
  const group = groupScope.value;
  if (group && (!groupReady.value || !group.value)) return;
  const version = selectionVersion;
  if (group) openingGroupedEditor.value = true;
  try {
    await openEditor(undefined, true, group);
  } catch (error) {
    if (version === selectionVersion) ElMessage.error(errorMessage(error, '内容编辑器加载失败'));
  } finally {
    if (version === selectionVersion) openingGroupedEditor.value = false;
  }
}

function reloadFirstPage() {
  page.value = 1;
  void loadContent();
}

function openGroupAssignment() {
  if (!canAssignGroup.value || !canAssignSelection.value || contentLoading.value || groupAssigning.value) return;
  groupAssignmentRecords.value = selectedRows.value.flatMap(({ recordId, rowVersion }) =>
    rowVersion ? [{ recordId, rowVersion }] : []);
  targetGroupValue.value = '';
  groupAssignmentError.value = '';
  groupAssignmentConflict.value = false;
  groupAssignmentVisible.value = true;
}

function refreshGroupAssignment() {
  groupAssignmentVisible.value = false;
  reloadFirstPage();
}

async function submitGroupAssignment() {
  const group = groupScope.value;
  const menuId = selectedMenu.value?.id;
  const groupValue = targetGroupValue.value.trim();
  if (!canAssignGroup.value || !group || !menuId || !groupAssignmentVisible.value || groupAssigning.value ||
      groupAssignmentConflict.value || !groupAssignmentRecords.value.length || !groupValue || groupValue.length > 512) return;
  const version = selectionVersion;
  const path = route.fullPath;
  groupAssigning.value = true;
  groupAssignmentError.value = '';
  try {
    const { updatedCount } = await assignNativeContentGroup({
      menuId, groupField: group.field, groupValue, records: groupAssignmentRecords.value
    });
    if (version !== selectionVersion || path !== route.fullPath) return;
    groupAssignmentVisible.value = false;
    groupAssignmentResult.value = { groupValue, updatedCount };
    reloadFirstPage();
  } catch (error) {
    if (version !== selectionVersion || path !== route.fullPath) return;
    groupAssignmentConflict.value = error instanceof AdminHttpError && error.conflictError;
    groupAssignmentError.value = errorMessage(error, '归组失败，请重试。');
  } finally {
    groupAssigning.value = false;
  }
}

function clearGroupedEditor() {
  if (groupReady.value || newRecordGroup.value || openingGroupedEditor.value) {
    editorVisible.value = false;
    editor.value = undefined;
  }
  openingGroupedEditor.value = false;
  newRecordGroup.value = undefined;
  groupReady.value = false;
  groupOptions.value = [];
  nextGroup.value = undefined;
  onlyImported.value = true;
  groupAssignmentVisible.value = false;
  groupAssignmentResult.value = undefined;
}

async function openEdit(row: NativeContentRow) {
  try {
    await openEditor(row.recordId, true);
  } catch (error) {
    ElMessage.error('内容详情加载失败');
  }
}

async function openCopy(row: NativeContentRow) {
  if (!selectedMenu.value || !languageId.value) return;
  const version = selectionVersion;
  try {
    // 加载现有数据
    const data = await loadNativeEditor({
      menuId: selectedMenu.value.id,
      languageId: languageId.value,
      recordId: row.recordId
    });
    if (version !== selectionVersion) return;
    newRecordGroup.value = undefined;
    // 清除 recordId，表示是新增模式
    editor.value = { ...data, recordId: undefined, rowVersion: undefined };
    // 填充表单数据（复制）
    contentForm.title = row.title || '';
    contentForm.sort = row.sort || 0;
    contentForm.values = {};
    contentForm.relations = {};
    for (const field of data.fields) {
      contentForm.values[field.fieldKey] = formValue((field.translatable ? data.payload : data.sharedPayload)[field.fieldKey], field);
    }
    editorVisible.value = true;
  } catch (error) {
    ElMessage.error('复制数据加载失败');
  }
}

async function openEditor(recordId?: number, showDrawer = true, group?: {field:string;value:string}) {
  if (!selectedMenu.value || !languageId.value) return;
  const version = selectionVersion;
  const menuId = selectedMenu.value.id, selectedLanguage = languageId.value;
  const data = await loadNativeEditor({
    menuId,
    languageId: selectedLanguage,
    recordId
  });
  if (version !== selectionVersion || selectedLanguage !== languageId.value || menuId !== selectedMenu.value?.id) return;
  if (group && (!siteContext.canOperate(menuId, 1) || !data.fields.some(field => field.fieldKey === group.field)))
    throw new Error(`当前栏目缺少可编辑的所属${groupLabel.value}字段。`);
  editor.value = data;
  newRecordGroup.value = group;
  contentForm.values = {};
  contentForm.relations = {};
  for (const field of data.fields) {
    contentForm.values[field.fieldKey] = formValue((field.translatable ? data.payload : data.sharedPayload)[field.fieldKey], field);
  }
  if (group) contentForm.values[group.field] = group.value;
  editorVisible.value = showDrawer;
}

async function save() {
  if (!editor.value) return;
  saving.value = true;
  try {
    const sharedPayload: Record<string, unknown> = {};
    const payload: Record<string, unknown> = {};
    const group = newRecordGroup.value;
    for (const field of editor.value.fields) {
      (field.translatable ? payload : sharedPayload)[field.fieldKey] = group?.field === field.fieldKey ? group.value : contentForm.values[field.fieldKey];
    }
    // 从父栏目跳转进入时新建的内容，自动归入该行的归属分类（t_contents.xgid）
    if (jumpXgid.value && !editor.value.recordId) {
      sharedPayload.xgid = jumpXgid.value;
    }
    await saveNativeDraft({
      recordId: editor.value.recordId ?? undefined,
      rowVersion: editor.value.rowVersion ?? undefined,
      menuId: editor.value.menuId,
      sharedPayload,
      payload
    });
    ElMessage.success('保存成功');
    editorVisible.value = false;
    await loadContent();
  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error);
    ElMessage.error(`保存失败: ${msg}`);
  } finally {
    saving.value = false;
  }
}

async function openTranslation(row: NativeContentRow) {
  if (!selectedMenu.value || !languageId.value) return;
  try {
    await ensureTranslationLanguages();
    const data = await loadNativeEditor({
      menuId: selectedMenu.value.id,
      languageId: languageId.value,
      recordId: row.recordId
    });
    translationSource.value = {
      recordId: row.recordId,
      title: row.title || '',
      values: { ...data.sharedPayload, ...data.payload },
      fields: data.fields.map(toContentEditorField)
    };
    translationVisible.value = true;
  } catch {
    ElMessage.error('翻译数据加载失败');
  }
}

async function handleTranslationSave(payload: {
  fields: Array<{ fieldKey: string; translatedText: string }>;
}) {
  if (!translationSource.value || !selectedMenu.value) return;
  translationSaving.value = true;
  try {
    const draftPayload: Record<string, unknown> = {};
    for (const field of translationSource.value.fields) {
      const hit = payload.fields.find((f) => f.fieldKey === field.fieldName);
      if (hit) draftPayload[field.fieldName] = hit.translatedText;
    }
    await saveNativeDraft({
      recordId: translationSource.value.recordId,
      menuId: selectedMenu.value.id,
      sharedPayload: {},
      payload: draftPayload
    });
    ElMessage.success('译文已保存');
    translationVisible.value = false;
    await loadContent();
  } catch (error) {
    ElMessage.error(`保存失败: ${error instanceof Error ? error.message : String(error)}`);
  } finally {
    translationSaving.value = false;
  }
}

async function openSeo(row: NativeContentRow) {
  seoRow.value = row;
  // 加载 SEO 数据
  try {
    const data = await http.get<{ content: { seoTitle: string; seoKeywords: string; seoDescription: string; detailPageUrl: string } }>(`/api/admin/v2/cms/content/${row.menuId}/${row.recordId}`);
    seoForm.seoTitle = data.content?.seoTitle ?? '';
    seoForm.seoKeywords = data.content?.seoKeywords ?? '';
    seoForm.seoDescription = data.content?.seoDescription ?? '';
    seoForm.detailPageUrl = data.content?.detailPageUrl ?? '';
  } catch {
    seoForm.seoTitle = '';
    seoForm.seoKeywords = '';
    seoForm.seoDescription = '';
    seoForm.detailPageUrl = '';
  }
  seoDrawerVisible.value = true;
}

async function saveSeo() {
  if (!seoRow.value) return;
  seoSaving.value = true;
  try {
    await http.post(`/api/admin/v2/cms/content/${seoRow.value.menuId}/${seoRow.value.recordId}/seo`, {
      seoTitle: seoForm.seoTitle,
      seoKeywords: seoForm.seoKeywords,
      seoDescription: seoForm.seoDescription
    });
    ElMessage.success('SEO 设置已保存');
    seoDrawerVisible.value = false;
  } catch {
    ElMessage.error('SEO 设置保存失败');
  } finally {
    seoSaving.value = false;
  }
}

function onSeoAiGenerate() {
  ElMessage.info('AI 生成功能暂未实现');
}

async function openPreview(row: NativeContentRow) {
  try {
    const data = await http.get<{ previewUrl: string; readableError?: string }>(`/api/admin/v2/cms/content/${row.menuId}/${row.recordId}/preview`);
    if (data.previewUrl) {
      window.open(data.previewUrl, '_blank');
    } else if (data.readableError) {
      ElMessage.warning(data.readableError);
    } else {
      ElMessage.warning('无法生成预览链接');
    }
  } catch {
    ElMessage.error('预览加载失败');
  }
}

async function openAudit(row: NativeContentRow) {
  try {
    const data = await http.get<{ histories: Array<{ oldContentId: number; contentId: number; title: string; operatorName: string; operatorAccount: string; operationTime: string; status: number; statusText: string; diffSummary: string; updateDescription: string; fields: Array<{ fieldKey: string; fieldName: string; updateBefore: unknown; updateAfter: unknown }> }>; auditTrail: Array<{ id: number; contentId: number; contentTitle: string; submitterName: string; submitDescription: string; submitTime: string; checkerName: string; checkDescription: string; checkTime: string; status: number; statusText: string }> }>(`/api/admin/v2/cms/content/${row.menuId}/${row.recordId}/history`);
    legacyHistory.value = data;
    historyVisible.value = true;
  } catch {
    ElMessage.error('审核信息加载失败');
  }
}

async function rollbackLegacy(item: { oldContentId: number; contentId: number }) {
  const menuId = selectedMenu.value?.id;
  if (!menuId || !item.contentId || !item.oldContentId) return;
  try {
    await ElMessageBox.confirm('确认回滚到该版本？当前内容中与此版本不一致的字段将被恢复。', '回滚版本', { type: 'warning' });
  } catch {
    return;
  }
  historyRolling.value = true;
  try {
    const result = await http.post<unknown>(`/api/admin/v2/cms/content/${menuId}/${item.contentId}/history/${item.oldContentId}/rollback`);
    historyVisible.value = false;
    await loadContent();
    if (typeof result === 'string' && result) {
      ElMessage.info(result);
    } else {
      ElMessage.success('回滚成功');
    }
  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error);
    if (msg.includes('无需回滚') || msg.includes('一致')) {
      ElMessage.info(msg);
    } else {
      ElMessage.error(`回滚失败：${msg}`);
    }
  } finally {
    historyRolling.value = false;
  }
}

// 从字段值中提取可预览的图片/视频资源地址（支持单个 URL、换行/逗号/分号分隔、JSON 数组）
function parsePreviewUrls(value: unknown): string[] {
  if (value == null) return [];
  const text = String(value).trim();
  if (!text) return [];
  let urls: string[];
  if (text.startsWith('[')) {
    try {
      const parsed = JSON.parse(text);
      urls = Array.isArray(parsed) ? parsed.map((item) => String(item).trim()).filter(Boolean) : [text];
    } catch {
      urls = [text];
    }
  } else {
    urls = text.split(/[\n,;]+/).map((s) => s.trim()).filter(Boolean);
  }
  return urls.map(resolveResourceUrl).filter(
    (u) => !!u && /\.(jpg|jpeg|png|gif|webp|svg|mp4|webm|ogg|mov)(\?|#|$)/i.test(u)
  );
}

// 解析 ui:/ 前缀为可访问 URL；站点包访问地址未就绪时返回空，待获取后自动重新渲染
function resolveResourceUrl(url: string): string { return resolveResourceReference(url); }

async function deleteRecord(row: NativeContentRow) {
  try {
    await ElMessageBox.confirm(`确认删除内容记录 ${row.title || row.recordId}？`, '删除内容', { type: 'warning' });
    await http.delete(`/api/admin/v2/cms/content/${selectedMenu.value?.id}/${row.recordId}`);
    ElMessage.success('内容已删除');
    await loadContent();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error('内容删除失败');
    }
  }
}

async function handleRecommendChange(row: NativeContentRow, field: string, value: boolean) {
  if (!selectedMenu.value) return;
  try {
    await http.patch(`/api/admin/v2/cms/content/${selectedMenu.value.id}/${row.recordId}/recommend`, { field, value });
    ElMessage.success('推荐状态已更新');
    await loadContent();
  } catch (error) {
    ElMessage.error('更新失败');
  }
}

async function handleSortChange(row: NativeContentRow, sort: number) {
  if (!selectedMenu.value) return;
  try {
    await updateNativeContentSort(selectedMenu.value.id, row.recordId, sort);
    ElMessage.success('排序已更新');
    await loadContent();
  } catch {
    ElMessage.error('排序更新失败');
  }
}

async function handleBatchOption(optionType: number, label: string) {
  if (!selectedMenu.value || selectedRows.value.length === 0 || !languageId.value) return;
  try {
    await ElMessageBox.confirm(`确认对选中的 ${selectedRows.value.length} 条记录执行「${label}」操作？`, '批量操作', { type: 'warning' });

    const ids = selectedRows.value.map(row => row.recordId);
    await http.post(`/api/admin/v2/cms/content/${selectedMenu.value.id}/option`, { ids, optionType });

    ElMessage.success(`已${label}`);
    selectedRows.value = [];
    await loadContent();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error('批量操作失败');
    }
  }
}

function handleBatchOptionMenuCommand(command: string) {
  const action = batchOptionMenuActions[command];
  if (action) {
    void handleBatchOption(action.optionType, action.label);
  }
}

async function handleBatchDelete() {
  if (!selectedMenu.value || selectedRows.value.length === 0) return;
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${selectedRows.value.length} 条记录？`, '批量删除', { type: 'warning' });

    const ids = selectedRows.value.map(row => row.recordId);
    const result = await http.post<BatchOperationResponse>(
      `/api/admin/v2/cms/content/${selectedMenu.value.id}/batch-delete`, { ids }
    );
    if (result.failureCount) {
      ElMessage.warning(`已删除 ${result.successCount} 条，失败 ${result.failureCount} 条。 ${result.failures[0]?.message ?? ''}`);
    } else {
      ElMessage.success('已删除');
    }
    selectedRows.value = [];
    await loadContent();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error('批量删除失败');
    }
  }
}

function formValue(value: unknown, field: NativeFieldDefinition): string | number | boolean | null {
  if (value === null || value === undefined) return defaultValue(field);
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return value;
  return String(value);
}

function defaultValue(field: NativeFieldDefinition): string | boolean | null {
  const type = field.fieldType.toLowerCase();
  if (['boolean', 'bool'].includes(type)) return false;
  if (['number', 'int', 'integer', 'decimal'].includes(type)) return null;
  return '';
}

function submitToBpp() {
  if (selectedRows.value.length === 0) return;
  ElMessage.success('已提交至BPP系统');
}

function openAuditDialog() {
  if (selectedRows.value.length === 0) return;
  auditForm.checkStatus = 0;
  auditForm.checkDescription = '';
  auditDialogVisible.value = true;
}

async function submitAudit() {
  if (!selectedMenu.value || selectedRows.value.length === 0) return;
  auditSubmitting.value = true;
  try {
    const ids = selectedRows.value.map(row => row.recordId);
    await auditContentV2(selectedMenu.value.id, {
      ids,
      checkStatus: auditForm.checkStatus,
      checkDescription: auditForm.checkDescription || undefined
    });

    ElMessage.success(`已审核 ${selectedRows.value.length} 条内容`);
    auditDialogVisible.value = false;
    selectedRows.value = [];
    await loadContent();
  } catch (error) {
    ElMessage.error('审核操作失败');
  } finally {
    auditSubmitting.value = false;
  }
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}
</script>

<style scoped>
.history-field__text {
  word-break: break-all;
}

.history-field__empty {
  color: var(--admin-text-muted);
}

.history-field__preview {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.history-field__key {
  font-family: Consolas, Monaco, monospace;
  color: var(--admin-text-primary);
}

.history-field__title {
  color: var(--admin-text-muted);
}

.cms-page {
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--admin-card-gap);
  flex: 1;
  min-height: 0;
  min-width: 0;
}

.cms-page h1 {
  margin: 0;
  font-size: var(--admin-font-size-page);
  font-weight: 600;
}

.cms-page p {
  margin: 4px 0 0;
  color: var(--admin-text-muted);
}

.admin-eyebrow {
  margin: 0 0 4px;
  font-size: var(--admin-font-size-small);
  font-weight: 500;
  color: var(--admin-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.cms-page__hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 18px;
  padding: var(--admin-card-padding-roomy);
  border: var(--admin-card-border);
  border-radius: var(--admin-card-radius);
  background: var(--admin-surface);
  box-shadow: var(--admin-shadow-card);
  min-width: 0;
}

.cms-page__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.content-management__single-status {
  display: flex;
  align-items: center;
}

.cms-card {
  border: var(--admin-card-border);
  border-radius: var(--admin-card-radius);
  background: var(--admin-surface);
  box-shadow: var(--admin-shadow-card);
  padding: var(--admin-card-padding);
  min-width: 0;
}

.cms-card__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.cms-card__header h2 {
  margin: 0;
  font-size: var(--admin-font-size-section);
  font-weight: 600;
}

.cms-card__header p {
  margin: 4px 0 0;
  font-size: var(--admin-font-size-small);
  color: var(--admin-text-muted);
}

.content-management__heading {
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
  flex-shrink: 0;
}

.content-management__heading-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.content-management__parent-title {
  min-width: 0;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.content-management__heading-title h2 {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.content-management__heading-title .el-tag {
  flex-shrink: 0;
}

.content-management__heading .cms-page__actions {
  margin-left: auto;
}

.content-management__layout {
  display: grid;
  grid-template-columns: minmax(var(--admin-tree-min-width), var(--column-navigation-width, var(--admin-content-tree-width))) var(--admin-tree-toggle-rail-width) minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  gap: 0;
  flex: 1;
  min-height: 0;
  min-width: 0;
}

.content-management__layout.is-tree-collapsed {
  grid-template-columns: var(--admin-tree-toggle-rail-width) minmax(0, 1fr);
}

.content-management__tree-panel {
  position: relative;
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
}

.content-management__tree .cms-card__header {
  flex-wrap: wrap;
}

.content-management__tree-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.content-management__tree {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.content-management__tree .cms-card__header {
  display: flex;
  flex-shrink: 0;
  justify-content: space-between;
  align-items: center;
}

.content-management__count {
  color: var(--admin-text-muted);
  font-size: var(--admin-font-size-small);
}

.content-management__filter {
  flex-shrink: 0;
  margin-bottom: 12px;
}

.content-management__tree .el-tree {
  flex: 1;
  overflow: auto;
  min-height: 0;
  min-width: 0;
}

.content-management__menu-node {
  display: flex;
  align-items: center;
  gap: 8px;
}

.content-management__menu-node-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 节点内容按内容宽度撑开，撑宽 .el-tree 以产生横向滚动条 */
.content-management__tree :deep(.el-tree-node__content) {
  width: max-content;
  min-width: 100%;
}

/* 放开多级（嵌套）节点的裁剪（EP 默认 overflow: hidden 会裁掉子级横向溢出）。
   展开/收起的折叠过渡用的是“内联” overflow: hidden，内联优先级高于普通规则，必须用
   !important 才能放开；动画期间用更具体的 .el-collapse-transition-*-active 规则重新裁剪。 */
.content-management__tree :deep(.el-tree-node__children) {
  overflow: visible !important;
}

.content-management__tree :deep(.el-tree-node__children.el-collapse-transition-enter-active),
.content-management__tree :deep(.el-tree-node__children.el-collapse-transition-leave-active) {
  overflow: hidden !important;
}

/* 横向滚动条常显：::-webkit-scrollbar 的 height 会把系统浮层滚动条转为经典滚动条。
   不要同时设置 scrollbar-width/scrollbar-color，否则会令下面的 ::-webkit-scrollbar 失效。 */
.content-management__tree .el-tree::-webkit-scrollbar {
  width: 10px;
  height: 10px;
}

.content-management__tree .el-tree::-webkit-scrollbar-track {
  background: transparent;
}

.content-management__tree .el-tree::-webkit-scrollbar-thumb {
  border-radius: 5px;
  background: #c1c1c1;
}

.content-management__tree .el-tree::-webkit-scrollbar-thumb:hover {
  background: #a8a8a8;
}

.content-management__menu-node.is-denied {
  opacity: 0.45;
  pointer-events: none;
}

.content-management__main {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: auto;
  flex: 1;
}

.content-management__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
  margin-top: 8px;
  flex-shrink: 0;
  padding: 8px 10px;
  border: 1px solid var(--admin-border);
  border-radius: var(--admin-radius-md);
  background: var(--admin-bg-subtle);
}

.content-management__filters {
  min-width: 0;
  flex-shrink: 0;
  margin-bottom: 8px;
  padding: 8px 10px;
  border: 1px solid var(--admin-border);
  border-radius: var(--admin-radius-md);
  background: var(--admin-surface);
}

.content-management__filters-row,
.content-management__filters-basic {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.content-management__filters-basic {
  flex: 1;
  overflow-x: auto;
}

.content-management__filters-basic > * {
  flex-shrink: 0;
}

.content-management__filters-toggle {
  flex-shrink: 0;
  margin-left: 0;
}

.content-management__filters-toggle .el-icon {
  margin-left: 4px;
}

.content-management__filters-advanced {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.content-management__actions :deep(.el-button + .el-button),
.content-management__filters :deep(.el-button + .el-button) {
  margin-left: 0;
}

.content-management__selection-state {
  flex: 0 0 auto;
  padding-right: 12px;
  border-right: 1px solid var(--admin-border);
  color: var(--admin-text-muted);
  font-size: var(--admin-font-size-small);
  line-height: 24px;
}

.content-management__selection-state.has-selection {
  color: var(--admin-color-primary-strong);
}

.content-management__action-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.content-management__group-label,
.content-management__filters-label {
  color: var(--admin-text-muted);
  font-size: var(--admin-font-size-small);
  white-space: nowrap;
}

.content-management__action-group--review {
  padding-left: 12px;
  border-left: 1px solid var(--admin-border);
}

.content-management__action-group--danger {
  padding-left: 12px;
  border-left: 1px solid var(--admin-color-danger-border);
}

.content-management__filter-control {
  width: 180px;
  max-width: 100%;
}

.content-management__filter-control--date {
  width: 340px;
}

.content-management__filter-control--status,
.content-management__filter-control--keyword {
  width: 160px;
}

.seo-drawer__ai {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.seo-drawer__ai-hint {
  color: var(--admin-text-muted);
  font-size: var(--admin-font-size-small);
}

.content-management__readonly {
  padding: 20px;
}

.content-management__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 60px;
  color: var(--admin-text-muted);
}

@media (max-width: 960px) {
  .content-management__layout {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(0, auto) minmax(0, 1fr);
  }

  .content-management__layout.is-tree-collapsed {
    grid-template-rows: minmax(0, 1fr);
  }

  .content-management__tree {
    max-height: 300px;
  }
}

@media (max-width: 640px) {
  .cms-page {
    padding: 0;
    gap: var(--admin-card-gap);
  }

  .cms-page__hero,
  .cms-card {
    padding: var(--admin-card-padding-compact);
  }

  .cms-page__hero {
    flex-direction: column;
  }

  .content-management__selection-state,
  .content-management__action-group--review,
  .content-management__action-group--danger {
    padding-left: 0;
    padding-right: 0;
    border: 0;
  }

  .content-management__filters,
  .content-management__actions {
    align-items: flex-start;
  }

  .content-management__filters-advanced .content-management__filter-control {
    width: 100%;
  }
}

@media (max-width: 960px) {
  .content-management__layout {
    grid-template-columns: var(--admin-tree-toggle-rail-width) minmax(0, 1fr);
    row-gap: var(--admin-card-gap);
  }

  .content-management__tree-panel {
    grid-column: 2;
    grid-row: 1;
  }

  .content-management__main {
    grid-column: 2;
    grid-row: 2;
  }

  .content-management__tree-toggle {
    grid-column: 1;
    grid-row: 1 / 3;
  }

  .content-management__layout.is-tree-collapsed > .content-management__main,
  .content-management__layout.is-tree-collapsed > .content-management__tree-toggle {
    grid-row: 1;
  }
}
</style>
