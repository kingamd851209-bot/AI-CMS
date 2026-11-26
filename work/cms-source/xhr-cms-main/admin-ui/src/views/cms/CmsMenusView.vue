<template>
  <section class="cms-page cms-menus">
    <div
      class="cms-menus__layout"
      :class="{ 'is-tree-collapsed': treeCollapsed }"
      :style="{ '--column-navigation-width': treeWidth == null ? undefined : `${treeWidth}px` }"
    >
      <div v-show="!treeCollapsed" class="cms-menus__tree-panel">
        <aside v-show="!treeCollapsed" id="cms-menus-tree" class="cms-card cms-menus__tree">
          <div class="cms-card__header">
            <div class="cms-menus__tree-title">
              <h2>栏目导航</h2>
            </div>
            <div class="cms-menus__tree-actions">
              <el-button v-if="developerWorkbenchEnabled && siteContext.isPlatformAdmin" size="small" @click="router.push('/admin/v2/site-build')">建站引导</el-button>
              <span>{{ flatMenus.length }} 项</span>
              <el-button type="primary" size="small" @click="openAiRecognizeDialog">AI识别</el-button>
            </div>
          </div>
          <el-tree
            v-if="rootMenus.length"
            ref="treeRef"
            node-key="id"
            :data="rootMenus"
            :props="treeProps"
            :lazy="true"
            :load="loadChildMenus"
            :default-expanded-keys="expandedKeys"
            :expand-on-click-node="false"
            highlight-current
            @node-click="selectMenu"
          >
            <template #default="{ data }">
              <span class="cms-menus__tree-node" :class="{ 'is-denied': !siteContext.hasMenuPermission(data.id) }">
                <span class="cms-menus__tree-node-name" :title="nodeDisplayName(data)">{{ nodeDisplayName(data) }}</span>
                <el-tag size="small" type="info">{{ nodeDisplayBizCode(data) }}</el-tag>
                <template v-if="data.id !== currentSite?.rootMenuId">
                  <el-tag v-if="nodeDisplayPageType(data) === 0" size="small" type="info">单篇</el-tag>
                  <el-tag v-else size="small" type="primary">列表</el-tag>
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
        class="cms-menus__tree-toggle"
        controls="cms-menus-tree"
        :breakpoint="1180"
      />

      <main v-loading="selectionLoading && !fieldLoading" element-loading-text="正在加载栏目…" class="cms-menus__main">
        <header class="cms-menus__toolbar">
          <div class="cms-menus__toolbar-info">
            <span
              v-if="currentSite"
              class="cms-menus__site-context"
              :title="`${currentSite.siteName || '未命名站点'} · ${currentSite.siteCode || '未设置编码'} · ${siteModeLabel(currentSite.siteMode)}`"
            >
              {{ currentSite.siteName || '未命名站点' }} · {{ currentSite.siteCode || '未设置编码' }} · {{ siteModeLabel(currentSite.siteMode) }}
            </span>
            <el-tag
              v-if="selectedMenu"
              size="small"
              class="cms-menus__current-menu"
              :title="selectedMenu.className || '未命名栏目'"
              data-testid="cms-menus-current-menu"
            >
              当前栏目：{{ selectedMenu.className || '未命名栏目' }}
              <el-tag size="small" type="info">{{ selectedMenu.id }}</el-tag>
            </el-tag>
          </div>
          <div class="cms-menus__toolbar-actions">
            <el-button size="small" @click="router.push('/admin/v2/sites-v2')">返回站点</el-button>
            <el-button size="small" :loading="loading" @click="loadMenus">刷新</el-button>
            <el-button size="small" type="primary" @click="openCreateDrawer">新建栏目</el-button>
          </div>
        </header>
        <el-tabs v-model="activeTab" class="cms-menus__tabs">
          <!-- 常用栏目编辑 -->
          <el-tab-pane label="编辑栏目信息" name="edit" class="cms-menus__edit-pane" data-testid="cms-menus-edit-pane">
            <section class="cms-card">
              <div class="cms-card__header">
                <div>
                  <h2>{{ form.id ? '编辑栏目' : '新建栏目' }}</h2>
                </div>
              </div>

              <el-form label-position="top" class="cms-form" @submit.prevent>
                <div class="cms-form__grid">
                  <el-form-item label="栏目名称">
                    <el-input v-model="form.className" placeholder="请输入栏目名称" />
                  </el-form-item>
                  <el-form-item label="父级栏目">
                    <el-tree-select
                      :model-value="form.parentId || undefined"
                      @update:model-value="form.parentId = $event"
                      :data="editParentMenuTree"
                      :props="{ label: 'className', children: 'children', disabled: 'disabled' }"
                      node-key="id"
                      :default-expanded-keys="[...editParentMenuTree.map(menu => menu.id), form.parentId]"
                      :render-after-expand="false"
                      check-strictly
                      filterable
                      placeholder="选择父级栏目"
                      style="width: 100%"
                    />
                  </el-form-item>
                  <el-form-item label="栏目类型">
                    <el-segmented
                      v-model="form.pageType"
                      :options="[
                        { label: '单篇', value: 0 },
                        { label: '列表', value: 1 }
                      ]"
                    />
                  </el-form-item>
                  <el-form-item label="排序号">
                    <el-input-number v-model="form.sort" :min="-9999" :max="999999" />
                  </el-form-item>
                  <el-form-item label="唯一标识">
                    <el-input v-model="form.identifies" placeholder="请输入唯一标识" />
                  </el-form-item>
                </div>

                <div class="cms-form__inline-row">
                  <el-form-item label="列表页地址" class="cms-form__inline-item">
                    <el-input v-model="form.listPageUrl" placeholder="/news/index.html" />
                    <div class="cms-form__field-hint">
                      可用变量：
                      <span
                        v-for="item in menuUrlVariables"
                        :key="item.token"
                        class="cms-form__field-hint-item"
                      >
                        <code class="cms-form__var" title="双击复制" @dblclick="copyUrlVariable(item.token)">{{ item.token }}</code>{{ item.desc }}
                      </span>
                    </div>
                  </el-form-item>
                  <el-form-item label="列表页模板" class="cms-form__inline-item">
                    <el-input v-model="form.listPageTemplate" placeholder="news/index.html">
                      <template #suffix>
                        <el-button size="small" type="primary" link class="ai-gen-btn" @click="openAiGenerator('list')">
                          <el-icon><MagicStick /></el-icon>
                          AI生成
                        </el-button>
                      </template>
                    </el-input>
                  </el-form-item>
                </div>

                <div class="cms-form__inline-row">
                  <el-form-item label="详情页地址" class="cms-form__inline-item">
                    <el-input v-model="form.detailPageUrl" placeholder="/news/details.html" />
                    <div class="cms-form__field-hint">
                      可用变量：
                      <span
                        v-for="item in menuUrlVariables"
                        :key="item.token"
                        class="cms-form__field-hint-item"
                      >
                        <code class="cms-form__var" title="双击复制" @dblclick="copyUrlVariable(item.token)">{{ item.token }}</code>{{ item.desc }}
                      </span>
                    </div>
                  </el-form-item>
                  <el-form-item label="详情页模板" class="cms-form__inline-item">
                    <el-input v-model="form.detailPageTemplate" placeholder="news/details.html">
                      <template #suffix>
                        <el-button size="small" type="primary" link class="ai-gen-btn" @click="openAiGenerator('detail')">
                          <el-icon><MagicStick /></el-icon>
                          AI生成
                        </el-button>
                      </template>
                    </el-input>
                  </el-form-item>
                </div>

                <div class="cms-form__switches">
                  <el-checkbox v-model="form.hasManageChannel">后台显示</el-checkbox>
                  <el-checkbox v-model="form.hasFrontChannel">前台显示</el-checkbox>
                  <el-checkbox v-model="form.isCanDeleted" title="勾选后允许删除该栏目">允许删除</el-checkbox>
                  <el-checkbox v-model="form.isSubMenu">二级栏目</el-checkbox>
                </div>

                <el-form-item label="包含操作">
                  <div class="menu-setting">
                    <div class="menu-setting__ops">
                      <el-checkbox
                        v-for="op in operationOptions"
                        :key="op.id"
                        :model-value="settingKeys.includes(op.id)"
                        @change="(val: boolean) => toggleSetting(op.id, val)"
                      >
                        {{ op.label }}
                      </el-checkbox>
                    </div>
                    <div class="menu-setting__quick">
                      <el-button size="small" @click="applyQuickSetting('standard')">标准权限</el-button>
                      <el-button size="small" @click="applyQuickSetting('no-publish')">标准权限 - 无发布</el-button>
                      <el-button size="small" @click="applyQuickSetting('form')">表单权限</el-button>
                      <el-button size="small" @click="applyQuickSetting('only-update')">仅有修改</el-button>
                      <el-button size="small" @click="applyQuickSetting('update-sort')">修改+排序</el-button>
                    </div>
                  </div>
                </el-form-item>

                <div class="cms-form__footer">
                  <el-button @click="resetForm">清空</el-button>
                  <el-button type="primary" :loading="saving" @click="saveMenu">保存栏目</el-button>
                  <el-button v-if="form.id" type="danger" plain :loading="saving" @click="deleteMenu">删除</el-button>
                </div>
              </el-form>
            </section>
          </el-tab-pane>

          <!-- 高级字段设置 -->
          <el-tab-pane label="栏目字段配置" name="metadata" data-testid="cms-menus-field-settings-pane">
            <div v-if="!form.id" class="cms-menus__tab-empty">
              <el-empty description="请先选择或创建栏目" />
            </div>
            <div v-else class="cms-menus__tab-content">
              <div class="cms-card__header">
                <h2>字段设置</h2>
                <div class="cms-menus__page-type-selector">
                  <span>栏目类型：</span>
                  <el-segmented
                    v-model="form.pageType"
                    :options="[
                      { label: '单篇', value: 0 },
                      { label: '列表', value: 1 }
                    ]"
                    @change="onPageTypeChange"
                  />
                </div>
                <el-button size="small" type="primary" plain :disabled="fieldLoading || fieldSaving" @click="newFieldVisible = true">新增业务字段</el-button>
                <el-button size="small" plain @click="syncDrawerVisible = true">数据模型同步</el-button>
              </div>

              <el-alert v-if="!fieldLoading && fieldRows.length > 0" type="info" :closable="false" title="公开检索会向本站访客提供匹配摘要。标题固定参与检索；正文、简介、备注和新增文本均须单独勾选，勿公开敏感字段。它与后台筛选独立；保存后同步重建，停用字段会撤销公开许可。停用扩展字段会保留已保存的内容值，不会物理删除数据。" />
              <section v-if="!fieldLoading && fieldRows.length > 0" class="cms-menus__field-advanced">
                <button
                  type="button"
                  class="cms-menus__field-advanced-toggle"
                  :aria-expanded="fieldSettingsExpanded"
                  aria-controls="cms-menus-field-advanced-panel"
                  data-testid="cms-menus-field-advanced-toggle"
                  @click="fieldSettingsExpanded = !fieldSettingsExpanded"
                >
                  <span>字段显示与预设</span>
                  <span class="cms-menus__field-filter-count">{{ visibleFieldNames.length }}/{{ fieldRows.length }}</span>
                  <el-icon :class="{ 'is-expanded': fieldSettingsExpanded }"><ArrowDown /></el-icon>
                </button>
                <div v-show="fieldSettingsExpanded" id="cms-menus-field-advanced-panel" class="cms-menus__field-advanced-content">
                  <div v-if="form.pageType !== 0" class="cms-menus__field-presets">
                    <el-button size="small" @click="applyFieldPreset('wordNews')">文字类型新闻</el-button>
                    <el-button size="small" @click="applyFieldPreset('imageNews')">图片类型新闻</el-button>
                    <el-button size="small" @click="applyFieldPreset('download')">资料下载类型</el-button>
                    <el-button size="small" @click="applyFieldPreset('wordLink')">文字类型友链</el-button>
                    <el-button size="small" @click="applyFieldPreset('imageLink')">图片类型友链</el-button>
                    <el-button size="small" @click="applyFieldPreset('effectTime')">生效-过期</el-button>
                    <el-button size="small" @click="applyFieldPreset('createTime')">发布时间</el-button>
                  </div>

                  <div class="cms-menus__field-filter">
                    <button
                      type="button"
                      class="cms-menus__field-filter-header"
                      :aria-expanded="fieldFilterExpanded"
                      aria-controls="cms-menus-field-filter-panel"
                      data-testid="cms-menus-field-filter-toggle"
                      @click="fieldFilterExpanded = !fieldFilterExpanded"
                    >
                      <span>字段筛选</span>
                      <span class="cms-menus__field-filter-count">{{ visibleFieldNames.length }}/{{ fieldRows.length }}</span>
                      <el-icon :class="{ 'is-expanded': fieldFilterExpanded }"><ArrowDown /></el-icon>
                    </button>
                    <el-collapse-transition>
                      <div v-show="fieldFilterExpanded" id="cms-menus-field-filter-panel" class="cms-menus__field-filter-content">
                        <el-collapse v-model="expandedFieldGroups" class="cms-menus__field-collapse">
                          <el-collapse-item
                            v-for="group in fieldGroups"
                            :key="group.key"
                            :name="group.key"
                          >
                            <template #title>
                              <div class="cms-menus__field-group-title">
                                <span>{{ group.label }}</span>
                                <span class="cms-menus__field-group-count">
                                  {{ getGroupSelectedCount(group.key) }}/{{ group.fields.length }}
                                </span>
                              </div>
                            </template>
                            <el-checkbox-group v-model="visibleFieldNames" class="cms-menus__field-group-items">
                              <el-checkbox
                                v-for="field in group.fields"
                                :key="field.fieldName"
                                :value="field.fieldName"
                              >
                                {{ field.fromName }}（{{ field.fieldName }}）
                              </el-checkbox>
                            </el-checkbox-group>
                          </el-collapse-item>
                        </el-collapse>
                      </div>
                    </el-collapse-transition>
                  </div>
                </div>
              </section>

              <div v-if="fieldLoading" class="cms-menus__loading">
                <el-icon class="is-loading"><Loading /></el-icon>
                <span>加载中...</span>
              </div>

              <div v-else class="cms-menus__field-table">
                <el-empty
                  v-if="!filteredFieldRows.length"
                  description="请展开“字段显示与预设”选择要配置的字段"
                />
                <el-table v-else class="cms-menus__field-grid" :data="filteredFieldRows" row-key="fieldName" stripe>
                  <el-table-column label="启用" width="60" fixed="left">
                    <template #default="{ row }">
                      <el-checkbox v-model="row.enabled" @change="onFieldEnabledChange(row)" />
                    </template>
                  </el-table-column>
                  <el-table-column prop="fromName" label="字段标题" width="120" fixed="left" />
                  <el-table-column prop="fieldName" label="字段名称" width="120" fixed="left" />
                  <el-table-column label="业务名称" width="120" fixed="left">
                    <template #default="{ row }">
                      <el-input v-model="row.title" size="small" :disabled="!row.enabled" />
                    </template>
                  </el-table-column>
                  <el-table-column prop="fieldType" label="字段类型" width="100">
                    <template #default="{ row }">
                      {{ fieldTypeName(row.fieldType) }}
                      <small v-if="row.fieldType === 'DateTime' && row.fieldName.startsWith('ext_')">{{ row.datePrecision === 'DAY' ? '年月日' : '精确到秒' }}</small>
                    </template>
                  </el-table-column>
                  <el-table-column v-if="form.pageType !== 0" label="列表" width="60">
                    <template #default="{ row }">
                      <el-checkbox v-model="row.isList" :disabled="!row.enabled || (row.fieldName.startsWith('ext_') && !['SingleLine', 'DateTime'].includes(row.fieldType))" />
                    </template>
                  </el-table-column>
                  <el-table-column v-if="form.pageType !== 0" label="后台筛选" width="84">
                    <template #default="{ row }">
                      <el-tooltip
                        :disabled="!isNonSearchableField(row) && row.enabled"
                        content="图片/视频/文件字段不支持作为后台筛选字段"
                        placement="top"
                      >
                        <el-checkbox v-model="row.isSearch" :disabled="!row.enabled || isNonSearchableField(row) || (row.fieldName.startsWith('ext_') && !['SingleLine', 'DateTime'].includes(row.fieldType))" />
                      </el-tooltip>
                    </template>
                  </el-table-column>
                  <el-table-column label="公开检索" width="96">
                    <template #default="{ row }">
                      <el-tooltip :content="isFixedPublicTitle(row) ? '标题固定参与公开检索' : '只允许公开文本；资源和系统字段不提供此项授权'" placement="top">
                        <el-checkbox
                          :model-value="isFixedPublicTitle(row) || (row.enabled && supportsPublicSearch(row) && row.publicSearch)"
                          :disabled="isFixedPublicTitle(row) || !row.enabled || !supportsPublicSearch(row)"
                          :aria-label="`${row.title || row.fromName}公开检索`"
                          @update:model-value="(value: unknown) => { row.publicSearch = value === true; }"
                        />
                      </el-tooltip>
                    </template>
                  </el-table-column>
                  <el-table-column label="只读" width="60">
                    <template #default="{ row }">
                      <el-checkbox v-model="row.isReadOnly" :disabled="!row.enabled" />
                    </template>
                  </el-table-column>
                  <el-table-column label="字段验证" width="180">
                    <template #default="{ row }">
                      <el-select v-model="row.validations" multiple size="small" placeholder="选择验证" :disabled="!row.enabled" style="width: 100%">
                        <el-option label="必填" value="required" />
                        <el-option label="电话" value="tel" />
                        <el-option label="邮箱" value="email" />
                        <el-option label="身份证" value="idCard" />
                        <el-option label="正整数" value="number" />
                        <el-option label="网址" value="webUrl" />
                      </el-select>
                    </template>
                  </el-table-column>
                  <el-table-column label="默认值" width="120">
                    <template #default="{ row }">
                      <el-input v-model="row.defaultValue" size="small" :disabled="!row.enabled || !['', 'counterNumber', 'counterDecimal'].includes(row.inputType) || (row.fieldName.startsWith('ext_') && ['Image', 'Video', 'File', 'DateTime'].includes(row.fieldType))" />
                    </template>
                  </el-table-column>
                  <el-table-column label="输入方式" width="200">
                    <template #default="{ row }">
                      <div class="cms-menus__input-type">
                        <el-select
                          v-model="row.inputType"
                          size="small"
                          :disabled="!row.enabled || isInputTypeLocked(row) || (row.fieldName.startsWith('ext_') && row.fieldType !== 'SingleLine') || (['counterNumber', 'counterDecimal'].includes(row.inputType) && !row.newlyAdded)"
                          placeholder="手动输入"
                          style="width: 96px"
                          @change="(val: string) => onInputTypeChange(row, val)"
                        >
                          <el-option label="手动输入" value="" />
                          <el-option label="固定枚举" value="enum" />
                          <el-option v-if="row.fieldName.startsWith('ext_') && row.fieldType === 'SingleLine'" label="统计数值" value="counterNumber" :disabled="!row.newlyAdded && row.inputType !== 'counterNumber'" />
                          <el-option v-if="row.inputType === 'counterDecimal'" label="统计数值（不接受千分位）" value="counterDecimal" />
                          <el-option label="栏目内容" value="content" />
                          <el-option v-if="!row.fieldName.startsWith('ext_')" label="栏目选项" value="menu" />
                        </el-select>
                        <el-button
                          v-if="(row.inputType === 'enum' || row.inputType === 'content' || row.inputType === 'menu') && !isInputTypeLocked(row)"
                          size="small"
                          text
                          type="primary"
                          @click="openInputTypeConfig(row)"
                        >配置</el-button>
                      </div>
                      <div v-if="['counterNumber', 'counterDecimal'].includes(row.inputType)">
                        <small>数字与单位分离</small>
                        <el-tooltip :trigger="['hover', 'focus']" :content="`0至9007199254740991，${row.inputType === 'counterDecimal' ? '不接受逗号' : '规范逗号'}，小数最多12位且小数有效数字最多15位。选填可清空，必填拒空；固定单位需在目标外保留，旧数据不自动迁移。`" placement="top">
                          <el-button link type="primary" size="small" aria-label="查看统计数值范围及清空规则">查看范围</el-button>
                        </el-tooltip>
                      </div>
                    </template>
                  </el-table-column>
                  <el-table-column label="提示信息" width="120">
                    <template #default="{ row }">
                      <el-input v-model="row.bak" size="small" :disabled="!row.enabled" />
                    </template>
                  </el-table-column>
                  <el-table-column label="最大字符" width="100">
                    <template #default="{ row }">
                      <el-input-number v-model="row.maxLength" size="small" :min="row.fieldName.startsWith('ext_') ? (row.fieldType === 'DateTime' ? (row.datePrecision === 'DAY' ? 10 : 19) : 1) : 0" :max="row.fieldName.startsWith('ext_') ? (row.fieldType === 'SingleLine' ? 500 : row.fieldType === 'MultiLine' ? 10000 : row.fieldType === 'Editor' ? 200000 : row.fieldType === 'DateTime' ? (row.datePrecision === 'DAY' ? 10 : 19) : 2048) : 999999" controls-position="right" :disabled="!row.enabled" />
                    </template>
                  </el-table-column>
                  <el-table-column label="排序" width="90">
                    <template #default="{ row }">
                      <el-input-number v-model="row.sort" size="small" :min="-9999" :max="999999" controls-position="right" :disabled="!row.enabled" @change="rebuildFieldOrder" />
                    </template>
                  </el-table-column>
                </el-table>
              </div>

              <div v-if="!fieldLoading" class="cms-form__footer cms-menus__field-footer">
                <el-button type="primary" :loading="fieldSaving" @click="saveFieldConfig">保存字段配置</el-button>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="SEO 设置" name="seo" data-testid="cms-menus-seo-pane">
            <section class="cms-card">
              <div class="cms-card__header">
                <h2>SEO 设置</h2>
              </div>
              <el-form label-position="top" class="cms-form" @submit.prevent>
                <el-form-item label="SEO 标题">
                  <el-input v-model="seoForm.seoTitle" />
                </el-form-item>
                <el-form-item label="SEO 关键词">
                  <el-input v-model="seoForm.seoKeywords" />
                </el-form-item>
                <el-form-item label="SEO 描述">
                  <el-input v-model="seoForm.seoDescription" type="textarea" :rows="3" />
                </el-form-item>
                <el-button type="primary" :disabled="!form.id" :loading="saving" @click="saveSeo">保存 SEO</el-button>
              </el-form>
            </section>
          </el-tab-pane>

          <el-dialog v-model="newFieldVisible" title="新增业务字段" width="420px" @closed="newFieldTitle = ''; newFieldType = 'SingleLine'; newFieldDatePrecision = 'SECOND'">
            <el-form label-position="top" @submit.prevent="addNewTextField">
              <el-form-item label="字段类型">
                <el-select v-model="newFieldType">
                  <el-option value="SingleLine" label="单行文本（可配置固定枚举／多选）" />
                  <el-option value="DateTime" label="日期／日期时间" />
                  <el-option value="MultiLine" label="多行纯文本（简介）" />
                  <el-option value="Editor" label="富文本（正文）" />
                  <el-option value="Image" label="图片" />
                  <el-option value="Video" label="视频" />
                  <el-option value="File" label="附件" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="newFieldType === 'DateTime'" label="日期精度">
                <el-select v-model="newFieldDatePrecision">
                  <el-option value="DAY" label="日期（仅年月日，不补时分秒）" />
                  <el-option value="SECOND" label="日期时间（精确到秒）" />
                </el-select>
                <small>日期支持0100至9999年；保存后不能更改精度，旧字段仍精确到秒。</small>
              </el-form-item>
              <el-form-item label="业务名称">
                <el-input v-model="newFieldTitle" maxlength="100" show-word-limit placeholder="例如：员工规模、成立年份" />
              </el-form-item>
              <p class="cms-menus__dialog-hint">保存后即可填写内容，无需重启。单行最多500字，多行最多10000字，正文源码最多200000字符。正文保留段落、列表、链接和图片，自动移除可执行内容及页面布局样式。图片、视频和附件每字段选择一个当前站点或全局资源，在内容编辑器维护，不设置字段默认值。字段类型保存后不能更改。</p>
            </el-form>
            <template #footer>
              <el-button @click="newFieldVisible = false">取消</el-button>
              <el-button type="primary" :disabled="!newFieldTitle.trim()" @click="addNewTextField">添加字段</el-button>
            </template>
          </el-dialog>

          <!-- 固定枚举编辑弹窗 -->
          <el-dialog v-model="enumEditorVisible" title="设置固定枚举值" width="420px">
            <div v-if="enumEditingRow">
              <p class="cms-menus__dialog-hint">字段：{{ enumEditingRow.fromName }}（{{ enumEditingRow.fieldName }}），选项值即存储与显示值</p>
              <el-form label-width="80px">
                <el-form-item label="选择方式">
                  <el-radio-group v-model="enumEditingRow.inputMultiple">
                    <el-radio :value="false">单选</el-radio>
                    <el-radio :value="true">多选</el-radio>
                  </el-radio-group>
                </el-form-item>
              </el-form>
              <div v-for="(item, index) in enumEditorRows" :key="index" class="cms-menus__enum-row">
                <el-input v-model="item.value" placeholder="枚举值" :disabled="index === 0 && enumEditorRows.length === 1" @input="syncEnumDraft" />
                <el-button v-if="enumEditorRows.length > 1" type="danger" text size="small" @click="enumEditorRows.splice(index, 1); syncEnumDraft()">移除</el-button>
              </div>
              <el-button size="small" type="primary" plain @click="enumEditorRows.push({ value: '' }); syncEnumDraft()">添加枚举值</el-button>
            </div>
            <template #footer>
              <el-button @click="enumEditorVisible = false">取消</el-button>
              <el-button type="primary" @click="saveEnumConfig">确定</el-button>
            </template>
          </el-dialog>

          <!-- 栏目内容选择弹窗 -->
          <el-dialog v-model="menuPickerVisible" :title="pickerMode === 'parent' ? '选择父栏目（可多选，编辑器下拉列出它们的全部直接子栏目）' : '选择栏目（可多选，取所选栏目内容的标题为 key、ID 为 value）'" width="480px">
            <el-form v-if="menuEditingRow" label-width="100px">
              <el-form-item label="选择方式">
                <el-radio-group v-model="menuEditingRow.inputMultiple">
                  <el-radio :value="false">单选</el-radio>
                  <el-radio :value="true">多选</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="pickerMode === 'content'" label="允许不选">
                <el-checkbox v-model="menuEditingRow.allowEmpty">下拉存在空选项</el-checkbox>
              </el-form-item>
            </el-form>
            <div class="cms-menus__menu-picker-selected">
              <span class="cms-menus__menu-picker-selected-label">已选栏目</span>
              <template v-if="menuPickerSelected.length">
                <el-tag
                  v-for="item in menuPickerSelected"
                  :key="item.id"
                  size="small"
                  closable
                  @close="removeMenuSelection(item.id)"
                >{{ item.className }}</el-tag>
              </template>
              <span v-else class="cms-menus__menu-picker-selected-empty">点击下方栏目加入</span>
            </div>
            <div v-if="pickerMode === 'content' && cascadeFields.length" class="cms-menus__cascade-ref">
              <div class="cms-menus__cascade-ref-title">
                级联上级栏目{{ cascadeParent ? '「' + cascadeParent.className + '」' : '' }}对应的字段（只读参考）
              </div>
              <ul class="cms-menus__cascade-ref-list">
                <li v-for="f in cascadeFields" :key="f.fieldName">
                  字段 {{ f.fieldName }}｜业务名称：{{ f.title || f.fromName }}｜输入方式：{{ inputTypeLabel(f.inputType) }}｜默认值/来源：{{ f.defaultValue || '（空）' }}
                </li>
              </ul>
            </div>
            <div class="cms-menus__menu-picker">
              <el-input v-model="menuPickerFilter" placeholder="搜索栏目" clearable class="cms-menus__menu-picker-filter" @input="onMenuPickerFilter" />
              <el-tree
                v-if="rootMenus.length"
                ref="menuPickerTreeRef"
                node-key="id"
                :data="rootMenus"
                :props="menuPickerTreeProps"
                :lazy="true"
                :load="loadChildMenus"
                :filter-node-method="menuPickerFilterNode"
                :default-expanded-keys="expandedKeys"
                highlight-current
                @node-click="onMenuPickerPick"
              >
                <template #default="{ data }">
                  <span class="cms-menus__menu-node" :class="{ 'is-selected': isMenuSelected(data.id) }">{{ data.className }}</span>
                </template>
              </el-tree>
              <el-empty v-else description="暂无栏目" />
            </div>
            <template #footer>
              <el-button @click="menuPickerVisible = false">关闭</el-button>
              <el-button type="primary" :loading="fieldSaving" @click="saveMenuPickerConfig">保存</el-button>
            </template>
          </el-dialog>

        </el-tabs>
      </main>
    </div>

    <!-- 新建栏目抽屉 -->
    <el-drawer
      v-model="createDrawerVisible"
      title="新建栏目"
      direction="rtl"
      size="500px"
      :close-on-click-modal="false"
    >
      <el-form label-position="top" class="cms-form" @submit.prevent>
        <el-form-item label="栏目名称" required>
          <el-input v-model="createForm.className" placeholder="请输入栏目名称" />
        </el-form-item>
        <el-form-item label="父级栏目">
          <el-tree-select
            v-model="createForm.parentId"
            :data="parentMenuTree"
            :props="{ label: 'className', value: 'id', children: 'children' }"
            check-strictly
            filterable
            placeholder="选择父级栏目"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="栏目类型">
          <el-segmented
            v-model="createForm.pageType"
            :options="[
              { label: '单篇', value: 0 },
              { label: '列表', value: 1 }
            ]"
          />
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number v-model="createForm.sort" :min="-9999" :max="999999" style="width: 100%" />
        </el-form-item>
        <template v-if="createForm.pageType === 1">
          <div class="cms-form__inline-row">
            <el-form-item label="列表页地址" class="cms-form__inline-item">
              <el-input v-model="createForm.listPageUrl" placeholder="/news/index.html" />
              <div class="cms-form__field-hint">
                可用变量：
                <span
                  v-for="item in menuUrlVariables"
                  :key="item.token"
                  class="cms-form__field-hint-item"
                >
                  <code class="cms-form__var" title="双击复制" @dblclick="copyUrlVariable(item.token)">{{ item.token }}</code>{{ item.desc }}
                </span>
              </div>
            </el-form-item>
            <el-form-item label="列表页模板" class="cms-form__inline-item">
              <el-input v-model="createForm.listPageTemplate" placeholder="news/index.html" />
            </el-form-item>
          </div>
          <div class="cms-form__inline-row">
            <el-form-item label="详情页地址" class="cms-form__inline-item">
              <el-input v-model="createForm.detailPageUrl" placeholder="/news/details.html" />
              <div class="cms-form__field-hint">
                可用变量：
                <span
                  v-for="item in menuUrlVariables"
                  :key="item.token"
                  class="cms-form__field-hint-item"
                >
                  <code class="cms-form__var" title="双击复制" @dblclick="copyUrlVariable(item.token)">{{ item.token }}</code>{{ item.desc }}
                </span>
              </div>
            </el-form-item>
            <el-form-item label="详情页模板" class="cms-form__inline-item">
              <el-input v-model="createForm.detailPageTemplate" placeholder="news/details.html" />
            </el-form-item>
          </div>
        </template>
        <div class="cms-form__switches">
          <el-checkbox v-model="createForm.hasManageChannel">后台显示</el-checkbox>
          <el-checkbox v-model="createForm.hasFrontChannel">前台显示</el-checkbox>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="createDrawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveNewMenu">保存</el-button>
      </template>
    </el-drawer>

    <!-- AI 识别栏目抽屉 -->
    <el-drawer
      v-model="aiDialogVisible"
      title="AI 识别栏目"
      direction="rtl"
      size="800px"
      class="ai-recognize-drawer"
    >
      <div v-if="aiDialogLoading" class="ai-dialog-loading">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>加载中...</span>
      </div>
      <div v-else-if="aiPackageFiles.length === 0" class="ai-dialog-empty">
        <el-empty description="当前站点未上传 UIPackage，请先上传" />
      </div>
      <div v-else class="ai-file-browser">
        <aside class="ai-file-sidebar">
          <div class="ai-sidebar-header">
            <h4>目录</h4>
          </div>
          <el-tree
            :data="aiDirectoryTree"
            :props="{ label: 'name', children: 'children' }"
            node-key="path"
            :default-expand-all="true"
            highlight-current
            @node-click="onDirectoryClick"
          >
            <template #default="{ node, data }">
              <span class="ai-tree-node">
                <span class="ai-tree-icon">📁</span>
                <span>{{ data.name }}</span>
              </span>
            </template>
          </el-tree>
        </aside>
        <main class="ai-file-main">
          <div class="ai-breadcrumb">
            <el-breadcrumb separator="/">
              <el-breadcrumb-item @click="navigateToPath('')">根目录</el-breadcrumb-item>
              <el-breadcrumb-item
                v-for="(part, index) in currentPathParts"
                :key="index"
                @click="navigateToPath(currentPathParts.slice(0, index + 1).join('/'))"
              >
                {{ part }}
              </el-breadcrumb-item>
            </el-breadcrumb>
          </div>
          <div class="ai-file-list">
            <div
              v-for="item in currentDirectoryItems"
              :key="item.path"
              class="ai-file-item"
              :class="{ 'is-selected': selectedFilePath === item.path }"
              @click="onItemClick(item)"
              @dblclick="onItemDblClick(item)"
            >
              <span class="ai-file-icon">{{ item.isDirectory ? '📁' : '📄' }}</span>
              <span class="ai-file-name">{{ item.name }}</span>
              <span v-if="!item.isDirectory" class="ai-file-action">
                <el-button type="primary" size="small" link @click.stop="openFile(item.path)">访问</el-button>
              </span>
            </div>
            <el-empty v-if="currentDirectoryItems.length === 0" description="此目录为空" />
          </div>
        </main>
      </div>
      <div v-if="selectedFilePath" class="ai-drawer-footer">
        <div class="ai-selected-info">
          <span>已选择：</span>
          <el-tag>{{ selectedFilePath }}</el-tag>
        </div>
        <el-button type="primary" @click="openSelectedFile">访问页面</el-button>
      </div>
    </el-drawer>

    <!-- 数据模型同步抽屉 -->
    <FieldModelSyncDrawer v-model="syncDrawerVisible" @synced="onFieldModelSynced" />
  </section>
</template>

<script setup lang="ts">
import ColumnNavigationRail from '@/components/common/ColumnNavigationRail.vue';
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Loading, MagicStick, ArrowDown } from '@element-plus/icons-vue';
import { http } from '@/api/http';
import { listUiPackageFiles, type UiPackageFileInfo } from '@/api/uiPackage';
import { useSiteContextStore } from '@/stores/siteContext';
import { useFieldConfig, supportsPublicSearch, type FieldRow } from './useFieldConfig';
import FieldModelSyncDrawer from './FieldModelSyncDrawer.vue';

interface MenuNode {
  id: number;
  bizCode?: string;
  identifies?: string;
  parentId: number;
  className: string;
  classEnName?: string;
  sort?: number;
  pageType?: number;
  hasManageChannel?: boolean;
  hasFrontChannel?: boolean;
  url?: string;
  listPageUrl?: string;
  listPageTemplate?: string;
  detailPageUrl?: string;
  detailPageTemplate?: string;
  seoTitle?: string;
  seoKeywords?: string;
  seoDescription?: string;
  setting?: string;
  children?: MenuNode[];
  hasChildren?: boolean;
  hasMenuField?: boolean;
  hasModelField?: boolean;
  isSubMenu?: boolean;
  isCanDeleted?: boolean;
}

/**
 * 列表页地址 / 详情页地址支持的变量（与后端 SiteHttpServer.URL_PLACEHOLDERS 保持一致）
 */
const menuUrlVariables = [
  { token: '{parentId}', desc: '上级栏目ID' },
  { token: '{classId}', desc: '当前栏目ID' },
  { token: '{pageIndex}', desc: '页码' },
  { token: '{pageSize}', desc: '每页条数' },
  { token: '{id}', desc: '内容ID（详情页）' },
  { token: '${className}', desc: '当前栏目名称' }
];

/** 双击复制地址变量 */
async function copyUrlVariable(token: string) {
  try {
    await navigator.clipboard.writeText(token);
    ElMessage.success(`已复制 ${token}`);
  } catch {
    ElMessage.error('复制失败，请手动选择复制');
  }
}

interface CurrentSiteSummary {
  siteId?: number | null;
  rootMenuId?: number | null;
  siteName?: string | null;
  siteCode?: string | null;
  globalMode?: boolean;
  siteMode?: string | null;
}

interface MenuWorkspaceResponse {
  currentSite?: CurrentSiteSummary | null;
  flat: MenuNode[];
  tree: MenuNode[];
}

const treeCollapsed = ref(false);
const treeWidth = ref<number>();
const loading = ref(false);
const saving = ref(false);
const router = useRouter();
const developerWorkbenchEnabled = import.meta.env.VITE_DEVELOPER_WORKBENCH === 'true';
const siteContext = useSiteContextStore();
const currentSite = ref<CurrentSiteSummary | null>(null);
const flatMenus = ref<MenuNode[]>([]);
const rootMenus = ref<MenuNode[]>([]);
const expandedKeys = ref<number[]>([]);
const selectedMenu = ref<MenuNode | null>(null);
const syncDrawerVisible = ref(false);
const activeTab = ref('edit');
const treeRef = ref<null | { store?: { nodesMap?: Record<string, { expanded: boolean }> } }>(null);

// 保存栏目后就地覆盖树节点展示信息（名称/编码/类型），避免整树重载造成的可视刷新。
// 树为懒加载，深层节点不在 rootMenus 中，故用这一层响应式覆盖渲染，改动即时生效且零重载。
const nodeLabelOverrides = ref<Record<number, { className?: string; bizCode?: string; pageType?: number }>>({});
function nodeDisplayName(data: MenuNode): string {
  return nodeLabelOverrides.value[data.id]?.className ?? data.className;
}
function nodeDisplayBizCode(data: MenuNode): string | undefined {
  return nodeLabelOverrides.value[data.id]?.bizCode ?? data.bizCode;
}
function nodeDisplayPageType(data: MenuNode): number | undefined {
  return nodeLabelOverrides.value[data.id]?.pageType ?? data.pageType;
}

// 新建栏目抽屉
const createDrawerVisible = ref(false);
const createForm = reactive({
  parentId: 0,
  className: '',
  classEnName: '',
  sort: 0,
  pageType: 1,
  hasManageChannel: true,
  hasFrontChannel: true,
  url: '',
  listPageUrl: '',
  listPageTemplate: '',
  detailPageUrl: '',
  detailPageTemplate: ''
});

// 父级栏目树（用于选择器）
const parentMenuTree = computed(() => {
  const rootId = currentSite.value?.rootMenuId;
  // 构建树结构
  const buildTree = (items: MenuNode[], parentId: number): MenuNode[] => {
    return items
      .filter(item => item.parentId === parentId)
      .map(item => ({
        ...item,
        children: buildTree(items, item.id)
      }));
  };
  if (!flatMenus.value.length) return [];
  // 从根节点开始构建
  if (rootId) {
    const rootNode = flatMenus.value.find(m => m.id === rootId);
    if (rootNode) {
      return [{ ...rootNode, children: buildTree(flatMenus.value, rootId) }];
    }
  }
  return buildTree(flatMenus.value, 0);
});

interface ParentMenuOption {
  id: number;
  className: string;
  disabled: boolean;
  children: ParentMenuOption[];
}

// 仅展示现有栏目；自身及其所有后代不允许选为父级，避免形成循环。
const editParentMenuTree = computed<ParentMenuOption[]>(() => {
  const toOptions = (menus: MenuNode[], ancestorDisabled = false): ParentMenuOption[] =>
    menus.map(menu => {
      const disabled = ancestorDisabled || menu.id === form.id;
      return {
        id: menu.id,
        className: nodeDisplayName(menu),
        disabled,
        children: toOptions(menu.children ?? [], disabled)
      };
    });
  return toOptions(parentMenuTree.value);
});

const treeProps = {
  label: 'className',
  children: 'children',
  isLeaf: (data: MenuNode) => !data.hasChildren
};

// 字段配置相关
const fieldMenuId = ref(0);
const fieldType = computed(() => form.pageType === 0 ? 1 : 0);
const { loading: fieldLoading, saving: fieldSaving, fieldRows, load: loadFieldConfig, save: saveFieldConfigData, reset: resetFieldConfig, addBusinessField } = useFieldConfig(fieldMenuId, fieldType);
let selectionVersion = 0;
const selectionLoading = ref(false);

// 字段筛选相关
const fieldSettingsExpanded = ref(false);
const fieldFilterExpanded = ref(false);
const visibleFieldNames = ref<string[]>([]); // 选中的字段名称
const expandedFieldGroups = ref<string[]>(['singleLine', 'note', 'photo', 'media', 'content']); // 展开的分组

// 判断是否有已配置的字段
const hasFieldConfig = computed(() => {
  return fieldRows.value.some(r => r.enabled);
});

// 所有字段选项（用于筛选器）
const allFieldOptions = computed(() => {
  return fieldRows.value.map(row => ({
    label: `${row.fromName}（${fieldTypeName(row.fieldType)}-${row.fieldName}）`,
    value: row.fieldName
  }));
});

// 字段分组定义
interface FieldGroup {
  key: string;
  label: string;
  fields: Array<{ fieldName: string; fromName: string; fieldType: string }>;
}

// 字段分组（按字段名称前缀和类型分组）
const fieldGroups = computed<FieldGroup[]>(() => {
  const groups: Record<string, Array<{ fieldName: string; fromName: string; fieldType: string }>> = {
    singleLine: [],
    note: [],
    photo: [],
    media: [],
    file: [],
    content: [],
    other: []
  };

  // 单行文本类型字段（包含基础字段和 singleLine 系列）
  const singleLineTypes = ['SingleLine', 'Text', 'singleline', 'text'];
  const singleLineFields = ['title', 'tags', 'hits']; // 归入单行文本的基础字段名

  // 按字段名称前缀和类型分组
  fieldRows.value.forEach(row => {
    const name = row.fieldName.toLowerCase();
    const type = row.fieldType?.toLowerCase() || '';

    if (name.startsWith('singleline')) {
      groups.singleLine.push(row);
    } else if (singleLineFields.includes(name) || (singleLineTypes.includes(type) && !name.startsWith('note') && !name.startsWith('content') && !name.startsWith('photo') && !name.startsWith('media'))) {
      // title、tags、hits 或单行文本类型的字段归入 singleLine 分组
      groups.singleLine.push(row);
    } else if (name.startsWith('note')) {
      groups.note.push(row);
    } else if (name.startsWith('photo')) {
      groups.photo.push(row);
    } else if (name.startsWith('media')) {
      groups.media.push(row);
    } else if (name.startsWith('file') || name.startsWith('attachment')) {
      groups.file.push(row);
    } else if (name.startsWith('content')) {
      groups.content.push(row);
    } else if (name.includes('time') || name.includes('date') || type === 'datetime') {
      // 时间相关字段归入 other（时间字段）
      groups.other.push(row);
    } else {
      groups.other.push(row);
    }
  });

  // 构建分组列表（只包含有字段的分组）
  const groupLabels: Record<string, string> = {
    singleLine: '单行文本 (singleLine)',
    note: '多行文本 (note)',
    photo: '图片字段 (photo)',
    media: '视频字段 (media)',
    file: '文件字段 (file)',
    content: '富文本 (content)',
    other: '时间字段'
  };

  return Object.entries(groups)
    .filter(([_, fields]) => fields.length > 0)
    .map(([key, fields]) => ({
      key,
      label: groupLabels[key] || key,
      fields
    }));
});

// 获取分组选中数量
function getGroupSelectedCount(groupKey: string): number {
  const group = fieldGroups.value.find(g => g.key === groupKey);
  if (!group) return 0;
  return group.fields.filter(f => visibleFieldNames.value.includes(f.fieldName)).length;
}

// 表格显示顺序快照（fieldName 列表）。仅在加载/勾选变化/排序值提交(失焦)时刷新，
// 避免编辑排序值过程中逐键重排导致行错位、写到其他行。
const displayOrder = ref<string[]>([]);

// 按 sort 降序重建显示顺序（大值在前，与内容管理一致）
function rebuildFieldOrder() {
  displayOrder.value = fieldRows.value
    .filter(row => visibleFieldNames.value.includes(row.fieldName))
    .slice()
    .sort((a, b) => b.sort - a.sort)
    .map(row => row.fieldName);
}

// 过滤后的表格数据 - 只显示选中的字段，顺序取自 displayOrder 快照（不响应 row.sort 变化）
const filteredFieldRows = computed(() => {
  const byName = new Map<string, FieldRow>(
    fieldRows.value.map(row => [row.fieldName, row] as [string, FieldRow])
  );
  return displayOrder.value
    .map(name => byName.get(name))
    .filter((row): row is FieldRow => Boolean(row));
});

// 标记是否正在初始化（避免初始化时触发启用逻辑）
let isInitializing = false;
// 记录已初始化的加载签名，避免重复初始化
let initializedFor: string | null = null;

// 监听栏目变化，重置筛选状态
watch(() => selectedMenu.value?.id, () => {
  initializedFor = null;
});

// 根据当前已加载字段配置初始化可见字段与折叠状态（消除缓存命中/网络加载与栏目切换的竞态）
function syncVisibleFieldsFromLoaded() {
  const signature = `${fieldMenuId.value}|${fieldRows.value.length}|${hasFieldConfig.value}`;
  if (initializedFor === signature) return;
  initializedFor = signature;
  isInitializing = true;
  if (hasFieldConfig.value) {
    // 有配置：只显示已启用的字段，筛选区保持收起，避免挤占字段表首屏。
    fieldFilterExpanded.value = false;
    visibleFieldNames.value = fieldRows.value.filter(r => r.enabled).map(r => r.fieldName);
  } else {
    // 没有配置：默认不选中任何字段，用户按需展开高级区选择。
    fieldFilterExpanded.value = false;
    visibleFieldNames.value = [];
  }
  rebuildFieldOrder();
  // 下一帧后重置标记
  requestAnimationFrame(() => {
    isInitializing = false;
  });
}

// 字段数据加载后（含缓存命中），初始化选中状态和折叠状态
watch(fieldRows, () => {
  syncVisibleFieldsFromLoaded();
}, { immediate: true });

// 监听字段筛选变化，同步启用状态
watch(visibleFieldNames, (newVal, oldVal) => {
  if (isInitializing) return;

  // 找出新增选中的字段（顺序即勾选顺序）
  const added = oldVal ? newVal.filter(name => !oldVal.includes(name)) : newVal;
  // 找出取消选中的字段
  const removed = oldVal ? oldVal.filter(name => !newVal.includes(name)) : [];

  // 排序基准：降序、步长 20、基准 900。已有启用字段则接在其下方（比当前最小值再小 20）。
  const enabledSorts = fieldRows.value.filter(r => r.enabled).map(r => r.sort);
  let nextSort = enabledSorts.length > 0 ? Math.min(...enabledSorts) - 20 : 900;

  // 新选中的字段自动启用，并按勾选顺序赋排序值
  added.forEach(fieldName => {
    const row = fieldRows.value.find(r => r.fieldName === fieldName);
    if (row && !row.enabled) {
      row.enabled = true;
      row.sort = nextSort;
      nextSort -= 20;
      // 触发启用变更逻辑
      onFieldEnabledChange(row);
    }
  });

  // 取消选中的字段自动禁用
  removed.forEach(fieldName => {
    const row = fieldRows.value.find(r => r.fieldName === fieldName);
    if (row) {
      row.enabled = false;
    }
  });

  rebuildFieldOrder();
}, { deep: true });

const aiDialogVisible = ref(false);
const aiDialogLoading = ref(false);
const aiPackageFiles = ref<UiPackageFileInfo[]>([]);
const selectedFilePath = ref('');
const currentPath = ref('');
const selectedAiGenType = ref<'list' | 'detail'>('list'); // 当前选择的 AI 生成类型
const selectedAiGenForm = ref<'edit' | 'create'>('edit'); // 当前表单模式
const aiEntrySource = ref<'menu-nav' | 'menu-edit'>('menu-nav'); // AI 识别入口来源

interface DirectoryNode {
  name: string;
  path: string;
  children?: DirectoryNode[];
}

interface FileItem {
  name: string;
  path: string;
  isDirectory: boolean;
}

const aiDirectoryTree = computed<DirectoryNode[]>(() => {
  return buildDirectoryTree(aiPackageFiles.value);
});

const currentPathParts = computed(() => {
  return currentPath.value ? currentPath.value.split('/').filter(Boolean) : [];
});

const currentDirectoryItems = computed<FileItem[]>(() => {
  const items: FileItem[] = [];
  const currentPrefix = currentPath.value ? currentPath.value + '/' : '';
  const seen = new Set<string>();

  for (const file of aiPackageFiles.value) {
    const path = file.relativePath;
    if (!path.startsWith(currentPrefix)) continue;

    const remaining = path.slice(currentPrefix.length);
    if (!remaining) continue;

    const firstPart = remaining.split('/')[0];
    const itemPath = currentPrefix + firstPart;
    const isDirectory = remaining.includes('/');

    if (seen.has(itemPath)) continue;
    seen.add(itemPath);

    items.push({
      name: firstPart,
      path: isDirectory ? itemPath + '/' : itemPath,
      isDirectory
    });
  }

  return items.sort((a, b) => {
    if (a.isDirectory && !b.isDirectory) return -1;
    if (!a.isDirectory && b.isDirectory) return 1;
    return a.name.localeCompare(b.name);
  });
});

function buildDirectoryTree(files: UiPackageFileInfo[]): DirectoryNode[] {
  const root: DirectoryNode[] = [];

  for (const file of files) {
    const parts = file.relativePath.split('/');
    let current = root;

    for (let i = 0; i < parts.length - 1; i++) {
      const part = parts[i];
      const path = parts.slice(0, i + 1).join('/');

      let node = current.find(n => n.name === part);
      if (!node) {
        node = { name: part, path: path + '/', children: [] };
        current.push(node);
      }

      if (!node.children) node.children = [];
      current = node.children;
    }
  }

  return sortDirectoryTree(root);
}

function sortDirectoryTree(nodes: DirectoryNode[]): DirectoryNode[] {
  return nodes
    .sort((a, b) => a.name.localeCompare(b.name))
    .map(node => ({
      ...node,
      children: node.children ? sortDirectoryTree(node.children) : undefined
    }));
}

function onDirectoryClick(data: DirectoryNode) {
  currentPath.value = data.path.replace(/\/$/, '');
}

function navigateToPath(path: string) {
  currentPath.value = path;
}

function onItemClick(item: FileItem) {
  selectedFilePath.value = item.path;
}

function onItemDblClick(item: FileItem) {
  if (item.isDirectory) {
    currentPath.value = item.path.replace(/\/$/, '');
  } else {
    openFile(item.path);
  }
}

function openFile(path: string) {
  selectedFilePath.value = path;
  openSelectedFile();
}

const form = reactive({
  id: 0,
  parentId: 0,
  className: '',
  classEnName: '',
  sort: 0,
  identifies: '',
  pageType: 1,
  hasManageChannel: true,
  hasFrontChannel: true,
  url: '',
  listPageUrl: '',
  listPageTemplate: '',
  detailPageUrl: '',
  detailPageTemplate: '',
  hasMenuField: false,
  hasModelField: false,
  setting: '',
  isSubMenu: false,
  isCanDeleted: false
});

// 与后端 AdminUserGroupV2ApiController.SETTING_TITLES 保持一致
const operationOptions = [
  { id: 1, label: '添加' },
  { id: 2, label: '修改' },
  { id: 3, label: '删除' },
  { id: 4, label: '发布/草稿' },
  { id: 5, label: '查看' },
  { id: 6, label: '排序' },
  { id: 7, label: '复制' },
  { id: 8, label: '首页置顶' },
  { id: 9, label: '首页热门' },
  { id: 10, label: '首页推荐' },
  { id: 11, label: '频道置顶' },
  { id: 12, label: '频道热门' },
  { id: 13, label: '频道推荐' },
  { id: 14, label: '栏目置顶' },
  { id: 15, label: '栏目热门' },
  { id: 16, label: '栏目推荐' },
  { id: 17, label: '导航推荐' },
  { id: 18, label: '右侧推荐' },
  { id: 19, label: '左侧推荐' },
  { id: 20, label: '导出数据' },
  { id: 21, label: '导入数据' },
  { id: 22, label: '审核状态' },
  { id: 23, label: '审核' }
];

const settingKeys = ref<number[]>([]);

const QUICK_SETTINGS: Record<string, number[]> = {
  standard: [1, 2, 3, 4, 5, 6, 22],
  'no-publish': [1, 2, 3, 5, 6, 22],
  form: [3, 4, 5, 20, 22],
  'only-update': [2],
  'update-sort': [2, 6]
};

function toggleSetting(id: number, val: boolean) {
  if (val) {
    if (!settingKeys.value.includes(id)) {
      settingKeys.value.push(id);
    }
  } else {
    settingKeys.value = settingKeys.value.filter((key) => key !== id);
  }
}

function applyQuickSetting(preset: string) {
  settingKeys.value = [...(QUICK_SETTINGS[preset] ?? [])];
}

function allOperationIds(): number[] {
  return operationOptions.map((op) => op.id);
}

const seoForm = reactive({
  seoTitle: '',
  seoKeywords: '',
  seoDescription: ''
});

const currentUrl = computed(() => form.pageType === 0 ? '/admin/menu_manage/{id}' : '/admin/model_manage/{id}');

// 监听站点切换，重新加载数据
watch(
  () => siteContext.currentSite?.id,
  (newSiteId, oldSiteId) => {
    if (newSiteId !== oldSiteId) {
      selectionVersion++;
      selectionLoading.value = false;
      resetFieldConfig();
      // 清空当前选中状态
      selectedMenu.value = null;
      rootMenus.value = [];
      flatMenus.value = [];
      resetForm();
      // 重新加载栏目数据
      void loadMenus();
    }
  }
);

onMounted(() => {
  void loadMenus();
});

async function loadMenus() {
  // 使用 siteContext 中当前选中的站点
  const selectedSite = siteContext.currentSite;
  if (!selectedSite) {
    currentSite.value = null;
    rootMenus.value = [];
    flatMenus.value = [];
    return;
  }

  loading.value = true;
  try {
    // 重新拉取时丢弃本地覆盖，以服务端数据为准
    nodeLabelOverrides.value = {};
    // 获取额外的站点信息（如 siteMode）
    const siteData = await http.get<{ currentSite?: CurrentSiteSummary }>('/api/admin/v2/cms/menus');

    // 合并 siteContext 和 API 返回的站点信息
    currentSite.value = {
      siteId: typeof selectedSite.id === 'number' ? selectedSite.id : Number(selectedSite.id),
      rootMenuId: selectedSite.rootMenuId,
      siteName: selectedSite.name,
      siteCode: selectedSite.code,
      globalMode: siteData.currentSite?.globalMode,
      siteMode: siteData.currentSite?.siteMode
    };

    const rootId = selectedSite.rootMenuId;

    // 获取根节点及其一级子节点
    if (rootId) {
      // 获取根节点详情
      const rootNode = await http.get<MenuNode>(`/api/admin/v2/cms/menus/${rootId}`);

      // 加载根节点的直接子节点（一级节点）
      const rootChildren = await http.get<MenuNode[]>('/api/admin/v2/cms/menus', {
        params: { parentId: rootId }
      });

      // 根节点包含一级子节点
      rootMenus.value = [{ ...rootNode, children: rootChildren, hasChildren: rootChildren.length > 0 }];
      // 默认展开站点根节点和一级栏目，更深层级保持折叠。
      expandedKeys.value = [rootId, ...rootChildren.map(menu => menu.id)];
    } else {
      // 没有 rootMenuId，直接加载顶级节点（parentId=0）
      const topNodes = await http.get<MenuNode[]>('/api/admin/v2/cms/menus', {
        params: { parentId: 0 }
      });
      rootMenus.value = topNodes;
      expandedKeys.value = topNodes.map(n => n.id);
    }

    // 后台异步加载所有菜单用于父级栏目选择框（不阻塞 UI）
    http.get<MenuWorkspaceResponse>('/api/admin/v2/cms/menus').then(fullData => {
      flatMenus.value = fullData.flat ?? [];
    });

    // 默认选中第一个一级子节点
    const firstRoot = rootMenus.value[0];
    if (!selectedMenu.value && firstRoot) {
      const firstChild = firstRoot.children?.[0];
      if (firstChild) {
        await selectMenu(firstChild);
      } else {
        await selectMenu(firstRoot);
      }
    }
  } catch (error) {
    ElMessage.error(errorMessage(error, '栏目加载失败'));
  } finally {
    loading.value = false;
  }
}

async function loadChildMenus(node: { data: MenuNode }, resolve: (data: MenuNode[]) => void) {
  try {
    const menuId = node.data.id ?? 0;
    const children = await http.get<MenuNode[]>('/api/admin/v2/cms/menus', {
      params: { parentId: menuId }
    });
    resolve(children);
  } catch (error) {
    ElMessage.error(errorMessage(error, '子栏目加载失败'));
    resolve([]);
  }
}

async function selectMenu(menu: MenuNode) {
  // 无权限栏目置灰可见但不可操作
  if (!siteContext.hasMenuPermission(menu.id)) {
    return;
  }
  const version = ++selectionVersion;
  selectionLoading.value = true;
  resetFieldConfig();
  selectedMenu.value = null;
  let detail: MenuNode;
  try {
    detail = await http.get<MenuNode>(`/api/admin/v2/cms/menus/${menu.id}`);
  } catch (error) {
    if (version === selectionVersion) {
      selectionLoading.value = false;
      ElMessage.error(errorMessage(error, '栏目加载失败'));
    }
    return;
  }
  if (version !== selectionVersion) return;
  selectedMenu.value = detail;
  Object.assign(form, {
    id: detail.id,
    parentId: detail.parentId ?? 0,
    className: detail.className ?? '',
    classEnName: detail.classEnName ?? '',
    sort: detail.sort ?? 0,
    identifies: detail.identifies ?? '',
    pageType: detail.pageType ?? 1,
    hasManageChannel: detail.hasManageChannel ?? true,
    hasFrontChannel: detail.hasFrontChannel ?? true,
    url: detail.url ?? '',
    listPageUrl: detail.listPageUrl ?? '',
    listPageTemplate: detail.listPageTemplate ?? '',
    detailPageUrl: detail.detailPageUrl ?? '',
    detailPageTemplate: detail.detailPageTemplate ?? '',
    hasMenuField: detail.hasMenuField ?? false,
    hasModelField: detail.hasModelField ?? false,
    isSubMenu: detail.isSubMenu ?? false,
    isCanDeleted: detail.isCanDeleted ?? false
  });
  const existingSetting = (detail.setting ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map(Number);
  // 未配置操作范围时默认全部勾选
  settingKeys.value = existingSetting.length ? existingSetting : allOperationIds();
  Object.assign(seoForm, {
    seoTitle: detail.seoTitle ?? '',
    seoKeywords: detail.seoKeywords ?? '',
    seoDescription: detail.seoDescription ?? ''
  });

  // 加载字段配置
  fieldMenuId.value = detail.id;
  try {
    await loadFieldConfig();
  } catch {
    // 忽略加载失败
  } finally {
    if (version === selectionVersion) selectionLoading.value = false;
  }
}

async function onFieldModelSynced() {
  await loadMenus();
  if (selectedMenu.value?.id) {
    fieldMenuId.value = selectedMenu.value.id;
    try {
      await loadFieldConfig();
    } catch {
      // 忽略加载失败
    }
  }
}

function openCreateDrawer() {
  // 重置表单
  Object.assign(createForm, {
    parentId: selectedMenu.value?.id ?? currentSite.value?.rootMenuId ?? 0,
    className: '',
    classEnName: '',
    sort: 0,
    pageType: 1,
    hasManageChannel: true,
    hasFrontChannel: true,
    url: '',
    listPageUrl: '',
    listPageTemplate: '',
    detailPageUrl: '',
    detailPageTemplate: ''
  });
  createDrawerVisible.value = true;
}

async function saveNewMenu() {
  if (!createForm.className.trim()) {
    ElMessage.warning('请输入栏目名称');
    return;
  }
  saving.value = true;
  try {
    const payload = {
      ...createForm,
      id: null,
      url: createForm.pageType === 0 ? '/admin/menu_manage/{id}' : '/admin/model_manage/{id}'
    };
    const detail = await http.post<MenuNode>('/api/admin/v2/cms/menus', payload);
    ElMessage.success('栏目创建成功');
    createDrawerVisible.value = false;
    await loadMenus();
    await selectMenu(detail);
  } catch (error) {
    ElMessage.error(errorMessage(error, '栏目创建失败'));
  } finally {
    saving.value = false;
  }
}

function resetForm() {
  selectedMenu.value = null;
  Object.assign(form, {
    id: 0,
    parentId: 0,
    className: '',
    classEnName: '',
    sort: 0,
    identifies: '',
    pageType: 1,
    hasManageChannel: true,
    hasFrontChannel: true,
    url: '',
    listPageUrl: '',
    listPageTemplate: '',
    detailPageUrl: '',
    detailPageTemplate: '',
    hasMenuField: false,
    hasModelField: false,
    setting: '',
    isCanDeleted: false
  });
  // 未选择栏目时默认全部操作勾选
  settingKeys.value = allOperationIds();
  Object.assign(seoForm, {
    seoTitle: '',
    seoKeywords: '',
    seoDescription: ''
  });
}

async function saveMenu() {
  if (!form.className.trim()) {
    ElMessage.warning('请输入栏目名称');
    return;
  }
  if (form.isSubMenu && form.pageType === 0) {
    ElMessage.warning('二级栏目不能是单篇，请将栏目类型改为「列表」或取消勾选二级栏目');
    return;
  }
  saving.value = true;
  try {
    form.setting = settingKeys.value.join(',');
    const payload = {
      ...form,
      id: form.id || null,
      url: currentUrl.value
    };
    const detail = await http.post<MenuNode>('/api/admin/v2/cms/menus', payload);
    ElMessage.success('栏目已保存');
    // 就地更新栏目导航显示，不重载整棵树（避免展开状态/滚动位置丢失造成的可视刷新）
    nodeLabelOverrides.value = {
      ...nodeLabelOverrides.value,
      [detail.id]: { className: detail.className, bizCode: detail.bizCode, pageType: detail.pageType }
    };
    const flat = flatMenus.value.find((m) => m.id === detail.id);
    if (flat) {
      Object.assign(flat, {
        parentId: detail.parentId,
        className: detail.className,
        classEnName: detail.classEnName,
        bizCode: detail.bizCode,
        pageType: detail.pageType
      });
    }
    await selectMenu(detail);
  } catch (error) {
    ElMessage.error(errorMessage(error, '栏目保存失败'));
  } finally {
    saving.value = false;
  }
}

async function saveSeo() {
  if (!form.id) {
    ElMessage.warning('请先选择栏目');
    return;
  }
  saving.value = true;
  try {
    await http.post<MenuNode>(`/api/admin/v2/cms/menus/${form.id}/seo`, seoForm);
    ElMessage.success('SEO 已保存');
    await loadMenus();
  } catch (error) {
    ElMessage.error(errorMessage(error, 'SEO 保存失败'));
  } finally {
    saving.value = false;
  }
}

async function deleteMenu() {
  if (!form.id) {
    return;
  }
  await ElMessageBox.confirm(`确认删除栏目”${form.className}”？`, '删除栏目', { type: 'warning' });
  saving.value = true;
  try {
    await http.delete<void>(`/api/admin/v2/cms/menus/${form.id}`);
    ElMessage.success('栏目已删除');
    resetForm();
    await loadMenus();
  } catch (error) {
    ElMessage.error(errorMessage(error, '栏目删除失败'));
  } finally {
    saving.value = false;
  }
}

const newFieldVisible = ref(false);
const newFieldTitle = ref('');
const newFieldDatePrecision = ref<'DAY' | 'SECOND'>('SECOND');
const newFieldType = ref<'SingleLine' | 'MultiLine' | 'Editor' | 'Image' | 'Video' | 'File' | 'DateTime'>('SingleLine');

function addNewTextField() {
  try {
    const fieldName = addBusinessField(newFieldTitle.value, newFieldType.value, newFieldDatePrecision.value);
    visibleFieldNames.value = [...new Set([...visibleFieldNames.value, fieldName])];
    rebuildFieldOrder();
    newFieldVisible.value = false;
    newFieldTitle.value = '';
    ElMessage.success('字段已添加，请保存字段配置');
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : '新增字段失败');
  }
}

// 字段配置相关方法
function onFieldEnabledChange(row: FieldRow) {
  if (row.enabled && form.pageType === 1 && !row.fieldName.startsWith('ext_')) {
    row.isList = true;
    // 只有第一个启用的字段默认选中搜索，其他需要手动选中
    const enabledCount = fieldRows.value.filter(r => r.enabled).length;
    if (enabledCount === 1) {
      row.isSearch = true;
    }
  }
}

async function saveFieldConfig(): Promise<boolean> {
  const enabledRows = fieldRows.value.filter(row => row.enabled);

  // 列表模式需要至少一个列表字段和一个搜索字段（清空配置时跳过校验）
  if (enabledRows.length > 0 && form.pageType === 1) {
    const listFields = enabledRows.filter(row => row.isList);
    const searchFields = enabledRows.filter(row => row.isSearch);
    if (listFields.length === 0) {
      ElMessage.warning('列表模式至少选择一个列表字段');
      return false;
    }
    if (searchFields.length === 0) {
      ElMessage.warning('列表模式至少选择一个后台筛选字段');
      return false;
    }
  }

  try {
    // 图片/视频/文件字段不支持搜索，保存前清除残留的 isSearch
    for (const row of enabledRows) {
      if (isNonSearchableField(row)) row.isSearch = false;
    }
    await saveFieldConfigData();
    ElMessage.success('字段配置已保存');
    // 保存后重新加载字段配置，确保数据同步
    await loadFieldConfig();
    // 更新筛选器显示已启用的字段
    visibleFieldNames.value = fieldRows.value.filter(r => r.enabled).map(r => r.fieldName);
    return true;
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '字段配置保存失败');
    return false;
  }
}

/** 「栏目内容」弹窗内的保存：保存字段配置（含关联字段名）并关闭弹窗 */
async function saveMenuPickerConfig() {
  if (!menuEditingRow.value) return;
  if (!menuPickerSelected.value.length) {
    ElMessage.warning(pickerMode.value === 'parent' ? '请至少选择一个父栏目' : '请至少选择一个栏目');
    return;
  }
  if (pickerMode.value === 'parent') {
    menuEditingRow.value.defaultValue =
      '{$' + menuPickerSelected.value.map((s) => s.id).join(',') + '_children$}';
    menuEditingRow.value.inputType = 'menu';
  } else {
    menuEditingRow.value.defaultValue =
      '{$' + menuPickerSelected.value.map((s) => s.id).join(',') + '_title_id$}';
    menuEditingRow.value.inputType = 'content';
  }
  if (await saveFieldConfig()) {
    menuPickerVisible.value = false;
  }
}

async function onPageTypeChange() {
  if (!form.id) return;

  saving.value = true;
  try {
    // 使用 saveMenu 的 API 端点更新栏目类型
    const payload = {
      id: form.id,
      className: form.className,
      parentId: form.parentId,
      pageType: form.pageType,
      sort: form.sort,
      hasManageChannel: form.hasManageChannel,
      hasFrontChannel: form.hasFrontChannel
    };
    const detail = await http.post<MenuNode>('/api/admin/v2/cms/menus', payload);
    ElMessage.success('栏目类型已更新');
    selectedMenu.value = detail;
    nodeLabelOverrides.value = {
      ...nodeLabelOverrides.value,
      [detail.id]: { ...nodeLabelOverrides.value[detail.id], pageType: detail.pageType }
    };

    // 重新加载字段配置
    fieldMenuId.value = form.id;
    try {
      await loadFieldConfig();
    } catch {
      // 忽略加载失败
    }
  } catch (error) {
    ElMessage.error(errorMessage(error, '栏目类型更新失败'));
    // 恢复原值
    form.pageType = form.pageType === 0 ? 1 : 0;
  } finally {
    saving.value = false;
  }
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}

/** 站点类型（逗号分隔多值）→ 展示文案 */
function siteModeLabel(siteMode?: string | null) {
  if (!siteMode || !siteMode.trim()) return '经典模板';
  const labels = siteMode
    .split(',')
    .map(item => item.trim().toUpperCase())
    .filter(Boolean)
    .map(mode => (mode === 'HEADLESS' ? 'Headless' : '经典模板'));
  return [...new Set(labels)].join(' / ') || '经典模板';
}

// 图片/视频/文件字段不支持作为搜索字段
function isFixedPublicTitle(row: { fieldName: string }): boolean {
  return row.fieldName === 'title' || (form.pageType === 0 && row.fieldName === 'className');
}

const nonSearchableTypes = ['Image', 'Photo', 'Video', 'File', 'Attachment'];
function isNonSearchableField(row: { fieldType: string; fieldName: string }): boolean {
  const name = row.fieldName.toLowerCase();
  if (name.startsWith('photo') || name.startsWith('media') || name.startsWith('file') || name.startsWith('attachment')) {
    return true;
  }
  return nonSearchableTypes.includes(row.fieldType || '');
}

// 图片/视频/文件/时间字段不支持输入方式配置（输入方式固定为手动输入）
const inputTypeLockedTypes = ['Image', 'Photo', 'Video', 'File', 'Attachment', 'DateTime'];
function isInputTypeLocked(row: { fieldType: string; fieldName: string }): boolean {
  const name = row.fieldName.toLowerCase();
  if (name.startsWith('photo') || name.startsWith('media') || name.startsWith('file') || name.startsWith('attachment')) {
    return true;
  }
  if (name.includes('time') || name.includes('date')) {
    return true;
  }
  return inputTypeLockedTypes.includes(row.fieldType || '');
}


const enumEditorVisible = ref(false);
const enumEditingRow = ref<FieldRow | null>(null);
const enumEditorRows = ref<Array<{ value: string }>>([]);
const menuPickerVisible = ref(false);
const menuEditingRow = ref<FieldRow | null>(null);
/** 栏目弹窗模式：content = 栏目内容（多选栏目）；parent = 栏目选项（可多选父栏目，取其子栏目并集） */
const pickerMode = ref<'content' | 'parent'>('content');

const menuPickerFilter = ref('');
const menuPickerTreeRef = ref<null | { filter: (value: string) => void }>(null);
const menuPickerTreeProps = { label: 'className', children: 'children', isLeaf: (data: MenuNode) => !data.hasChildren };
/** 栏目内容：已选栏目（点击树节点加入/移出） */
const menuPickerSelected = ref<Array<{ id: number; className: string }>>([]);

/** 级联反显：选到二级栏目并选择“级联”时，展示当前栏目中「栏目内容 = 该二级栏目的上级栏目」的字段（只读参考，不改数据） */
const cascadeParent = ref<MenuNode | null>(null);
const cascadeFields = ref<FieldRow[]>([]);

/** 从 defaultValue 解析栏目绑定 ID 列表：支持 {$101_title_id$} / {$101,102_title_id$} / {$101_children$} */
function parseBindingIds(defaultValue?: string): number[] {
  const dv = (defaultValue || '').trim();
  if (!dv.startsWith('{$') || !dv.endsWith('$}')) return [];
  const first = dv.slice(2, -2).split('_')[0];
  if (!/^[\d,]+$/.test(first)) return [];
  return first
    .split(',')
    .map((s) => Number(s.trim()))
    .filter((n) => Number.isFinite(n));
}

/** 输入方式显示值映射 */
function inputTypeLabel(inputType?: string): string {
  const map: Record<string, string> = {
    '': '手动输入',
    enum: '固定枚举',
    counterNumber: '统计数值（显式约束）',
    counterDecimal: '统计数值（不接受千分位）',
    content: '栏目内容',
    menu: '栏目选项'
  };
  return map[inputType ?? ''] ?? (inputType || '手动输入');
}

/** 从 defaultValue 解析已选栏目：支持 {$101_title_id$} 与 {$101,102_title_id$}；child 语法不在此回显 */
function parseMenuSelection(defaultValue?: string): Array<{ id: number; className: string }> {
  const dv = (defaultValue || '').trim();
  if (!dv.startsWith('{$') || !dv.endsWith('$}')) return [];
  const inner = dv.slice(2, -2);
  if (inner.includes('child_') || inner.endsWith('_children')) return [];
  const first = inner.split('_')[0];
  if (!/^[\d,]+$/.test(first)) return [];
  return first
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((id) => {
      const num = Number(id);
      const menu = flatMenus.value.find((m) => Number(m.id) === num);
      return { id: num, className: menu?.className || `#${id}` };
    })
    .filter((item) => Number.isFinite(item.id));
}

/** 从 defaultValue 解析父栏目：{$109,110_children$} → [{id:109},{id:110}]；非该形式返回 [] */
function parseParentSelection(defaultValue?: string): Array<{ id: number; className: string }> {
  const dv = (defaultValue || '').trim();
  if (!dv.startsWith('{$') || !dv.endsWith('$}')) return [];
  const parts = dv.slice(2, -2).split('_');
  if (parts.length !== 2 || parts[1] !== 'children') return [];
  return parts[0]
    .split(',')
    .map((s) => s.trim())
    .filter((s) => /^\d+$/.test(s))
    .map((s) => {
      const num = Number(s);
      const menu = flatMenus.value.find((m) => Number(m.id) === num);
      return { id: num, className: menu?.className || `#${num}` };
    });
}

function isMenuSelected(id: number | string): boolean {
  return menuPickerSelected.value.some((s) => s.id === Number(id));
}

function removeMenuSelection(id: number) {
  menuPickerSelected.value = menuPickerSelected.value.filter((s) => s.id !== id);
}

function onInputTypeChange(row: FieldRow, val: string) {
  row.inputType = val;
  if (row.fieldName.startsWith('ext_') && val === '') row.defaultValue = '';
  if (!['enum', 'content', 'menu'].includes(val)) row.inputMultiple = false;
  if (val !== 'content') row.allowEmpty = false;
  if (val !== 'content') row.cascadeParentField = '';
  if (val === 'enum') {
    openEnumConfig(row);
  } else if (val === 'content') {
    openMenuConfig(row, 'content');
  } else if (val === 'menu') {
    openMenuConfig(row, 'parent');
  }
}

function openInputTypeConfig(row: FieldRow) {
  if (row.inputType === 'enum') openEnumConfig(row);
  else if (row.inputType === 'content') openMenuConfig(row, 'content');
  else if (row.inputType === 'menu') openMenuConfig(row, 'parent');
}


function openEnumConfig(row: FieldRow) {
  enumEditingRow.value = row;
  row.inputMultiple = Boolean(row.inputMultiple);
  const dv = row.defaultValue || '';
  const values = dv && !dv.startsWith('{$') ? dv.split(',').map((s) => s.trim()).filter(Boolean) : [];
  enumEditorRows.value = values.length ? values.map((v) => ({ value: v })) : [{ value: '' }];
  enumEditorVisible.value = true;
}

function syncEnumDraft() {
  // 占位：随用户输入即时更新，保存时统一回写
}

function saveEnumConfig() {
  if (!enumEditingRow.value) return;
  const values = enumEditorRows.value.map((r) => r.value.trim()).filter(Boolean);
  if (values.length === 0) {
    ElMessage.warning('请至少设置一个枚举值');
    return;
  }
  if (enumEditingRow.value.fieldName.startsWith('ext_') && (new Set(values).size !== values.length || values.some(value => /[,\r\n|:=]/.test(value) || value.startsWith('{$')))) {
    ElMessage.warning('选项不能重复或包含逗号、换行、竖线、冒号、等号');
    return;
  }
  enumEditingRow.value.defaultValue = values.join(',');
  enumEditingRow.value.inputType = 'enum';
  enumEditorVisible.value = false;
}

function openMenuConfig(row: FieldRow, mode: 'content' | 'parent' = 'content') {
  menuEditingRow.value = row;
  pickerMode.value = mode;
  row.inputMultiple = Boolean(row.inputMultiple);
  menuPickerFilter.value = '';
  menuPickerSelected.value =
    mode === 'parent' ? parseParentSelection(row.defaultValue) : parseMenuSelection(row.defaultValue);
  cascadeParent.value = null;
  cascadeFields.value = [];
  if (!rootMenus.value.length) {
    void loadMenus();
  }
  // 再次打开时回显已选二级栏目的级联关系（flatMenus 未就绪时由 watch(flatMenus) 兜底）
  if (mode === 'content' && flatMenus.value.length) {
    applyCascadeForSelection();
  }
  menuPickerVisible.value = true;
}

// flatMenus 异步加载完成后，补齐已打开弹窗的级联回显
watch(flatMenus, (val) => {
  if (val.length && menuPickerVisible.value && pickerMode.value === 'content') {
    applyCascadeForSelection();
  }
});

function menuPickerFilterNode(value: string, data: MenuNode): boolean {
  if (!value) return true;
  return (data.className || '').toLowerCase().includes(value.toLowerCase());
}

function onMenuPickerFilter(value: string) {
  menuPickerTreeRef.value?.filter(value);
}

function onMenuPickerPick(data: MenuNode) {
  if (!menuEditingRow.value) return;
  const id = Number(data.id);
  if (menuEditingRow.value.fieldName.startsWith('ext_') && pickerMode.value === 'content' && data.pageType !== 1) {
    ElMessage.warning('关联内容请选择列表栏目');
    return;
  }
  if (pickerMode.value === 'parent') {
    if (isMenuSelected(id)) {
      removeMenuSelection(id);
    } else {
      menuPickerSelected.value.push({ id, className: data.className });
    }
    return;
  }
  if (isMenuSelected(id)) {
    removeMenuSelection(id);
    applyCascadeForSelection();
    return;
  }
  menuPickerSelected.value.push({ id, className: data.className });
  menuEditingRow.value.inputType = 'content';
  void maybeCascade(id);
}

/** 当前栏目中、输入方式为“栏目内容”且绑定到指定上级栏目的启用字段 */
function parentBoundFields(parent: MenuNode): FieldRow[] {
  return fieldRows.value.filter(
    (r) => r.enabled && r.inputType === 'content' && parseBindingIds(r.defaultValue).includes(Number(parent.id))
  );
}

/** 按已保存的级联字段回显级联关系（再次打开弹窗时使用，不弹确认框） */
function applyCascadeForSelection() {
  cascadeParent.value = null;
  cascadeFields.value = [];
  if (pickerMode.value !== 'content') return;
  const parentField = menuEditingRow.value?.cascadeParentField;
  if (!parentField) return;
  const field = fieldRows.value.find((r) => r.fieldName === parentField);
  if (!field) return;
  const parentIds = parseBindingIds(field.defaultValue);
  cascadeParent.value = parentIds.length
    ? flatMenus.value.find((m) => Number(m.id) === parentIds[0]) ?? null
    : null;
  cascadeFields.value = [field];
}

/** 选到二级栏目（isSubMenu）时询问是否级联，确认后只读反显「当前栏目中绑定到该二级栏目的上级栏目」的字段 */
async function maybeCascade(menuId: number) {
  if (menuEditingRow.value?.fieldName.startsWith('ext_')) {
    menuEditingRow.value.cascadeParentField = '';
    cascadeParent.value = null;
    cascadeFields.value = [];
    return;
  }
  const node = flatMenus.value.find((m) => Number(m.id) === menuId);
  if (!node || node.isSubMenu !== true || !node.parentId) {
    // 非二级栏目：按当前已选二级栏目重新推导，避免误清其它级联
    applyCascadeForSelection();
    return;
  }
  try {
    await ElMessageBox.confirm('该栏目为二级栏目，是否级联显示上级栏目对应的字段？', '是否级联', {
      confirmButtonText: '级联',
      cancelButtonText: '不级联',
      type: 'warning'
    });
  } catch {
    // 选择“不级联”：不显示、不保存级联关系
    cascadeParent.value = null;
    cascadeFields.value = [];
    if (menuEditingRow.value) menuEditingRow.value.cascadeParentField = '';
    return;
  }
  cascadeParent.value = null;
  cascadeFields.value = [];
  const parent = flatMenus.value.find((m) => Number(m.id) === Number(node.parentId));
  if (!parent) {
    ElMessage.warning('未找到上级栏目');
    if (menuEditingRow.value) menuEditingRow.value.cascadeParentField = '';
    return;
  }
  const matched = parentBoundFields(parent);
  cascadeParent.value = parent;
  cascadeFields.value = matched;
  // 记录级联的上级字段名，随字段配置一并保存
  if (menuEditingRow.value) {
    menuEditingRow.value.cascadeParentField = matched.length ? matched[0].fieldName : '';
  }
  if (!matched.length) {
    ElMessage.warning(`当前栏目无绑定到上级栏目「${parent.className}」的字段`);
  }
}

function fieldTypeName(fieldType: string): string {  const typeMap: Record<string, string> = {    'SingleLine': '单行文本',
    'MultiLine': '多行文本',
    'Image': '图片上传',
    'File': '文件上传',
    'Video': '视频上传',
    'Editor': '编辑器',
    'DateTime': '日期时间',
    'RichText': '编辑器',
    'Textarea': '多行文本',
    'Text': '单行文本',
    'Photo': '图片上传',
    'Attachment': '文件上传'
  };
  return typeMap[fieldType] || fieldType;
}

// 字段预设配置（字段名称对应新系统 cms_field_setting 表中的 fieldName）
const fieldPresets: Record<string, { fields: string[]; listFields: string[]; searchFields: string[]; webUrlFields: string[] }> = {
  // 文字类型新闻：标题、作者、来源、外部链接、简介、内容、发布时间、生效时间、过期时间
  wordNews: {
    fields: ['title', 'singleLine2', 'singleLine3', 'singleLine4', 'note1', 'content1', 'createTime', 'entryIntoForceTime', 'expirationTime'],
    listFields: ['title', 'createTime'],
    searchFields: ['title', 'createTime'],
    webUrlFields: ['singleLine4']
  },
  // 图片类型新闻：同文字类型新闻 + 图片
  imageNews: {
    fields: ['title', 'singleLine2', 'singleLine3', 'singleLine4', 'note1', 'content1', 'createTime', 'entryIntoForceTime', 'expirationTime', 'photo1'],
    listFields: ['title', 'createTime', 'photo1'],
    searchFields: ['title', 'createTime'],
    webUrlFields: ['singleLine4']
  },
  // 资料下载类型：标题、附件、发布时间、生效时间、过期时间
  download: {
    fields: ['title', 'file1', 'createTime', 'entryIntoForceTime', 'expirationTime'],
    listFields: ['title', 'file1', 'createTime'],
    searchFields: ['title', 'createTime'],
    webUrlFields: []
  },
  // 文字类型友链：标题、外部链接、发布时间、生效时间、过期时间
  wordLink: {
    fields: ['title', 'singleLine4', 'createTime', 'entryIntoForceTime', 'expirationTime'],
    listFields: ['title', 'singleLine4', 'createTime'],
    searchFields: ['title', 'createTime'],
    webUrlFields: ['singleLine4']
  },
  // 图片类型友链：标题、外部链接、发布时间、生效时间、过期时间、图片
  imageLink: {
    fields: ['title', 'singleLine4', 'createTime', 'entryIntoForceTime', 'expirationTime', 'photo1'],
    listFields: ['title', 'singleLine4', 'createTime', 'photo1'],
    searchFields: ['title', 'createTime'],
    webUrlFields: ['singleLine4']
  },
  // 生效-过期：生效时间、过期时间
  effectTime: {
    fields: ['entryIntoForceTime', 'expirationTime'],
    listFields: [],
    searchFields: [],
    webUrlFields: []
  },
  // 发布时间：发布时间
  createTime: {
    fields: ['createTime'],
    listFields: ['createTime'],
    searchFields: ['createTime'],
    webUrlFields: []
  }
};

function applyFieldPreset(presetKey: string) {
  const preset = fieldPresets[presetKey];
  if (!preset) return;

  // 先重置所有字段
  fieldRows.value.forEach(row => {
    row.enabled = false;
    row.isList = false;
    row.isSearch = false;
    row.validations = row.validations.filter(v => v !== 'webUrl');
  });

  // 应用预设
  const fieldNameSet = new Set(preset.fields.map(f => f.toLowerCase()));
  const listFieldSet = new Set(preset.listFields.map(f => f.toLowerCase()));
  const searchFieldSet = new Set(preset.searchFields.map(f => f.toLowerCase()));
  const webUrlFieldSet = new Set(preset.webUrlFields.map(f => f.toLowerCase()));

  const enabledFieldNames: string[] = [];

  fieldRows.value.forEach(row => {
    const fieldName = row.fieldName.toLowerCase();
    if (fieldNameSet.has(fieldName)) {
      row.enabled = true;
      row.isList = listFieldSet.has(fieldName);
      row.isSearch = searchFieldSet.has(fieldName);
      if (webUrlFieldSet.has(fieldName) && !row.validations.includes('webUrl')) {
        row.validations.push('webUrl');
      }
      enabledFieldNames.push(row.fieldName);
    }
  });

  // 同步更新字段筛选选中状态
  visibleFieldNames.value = enabledFieldNames;

  ElMessage.success('已应用预设配置');
}

async function openAiRecognizeDialog() {
  if (!currentSite.value?.siteId) {
    ElMessage.warning('请先选择站点');
    return;
  }
  // 从栏目导航进入，设置来源
  aiEntrySource.value = 'menu-nav';
  aiDialogVisible.value = true;
  aiDialogLoading.value = true;
  aiPackageFiles.value = [];
  selectedFilePath.value = '';
  currentPath.value = '';
  try {
    aiPackageFiles.value = await listUiPackageFiles(currentSite.value.siteId);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '获取文件列表失败');
  } finally {
    aiDialogLoading.value = false;
  }
}

/**
 * 从 URL 地址中解析文件路径
 * 例如: /news/index.html -> news/index.html
 */
function extractFilePath(url: string): string {
  if (!url) return '';
  // 去掉前导斜杠
  return url.replace(/^\/+/, '');
}

/**
 * 检查文件是否存在于 UI Package 文件列表中
 * 支持智能匹配：如果精确匹配失败，尝试匹配同名但扩展名不同的文件
 */
async function checkFileExists(filePath: string): Promise<{ exists: boolean; actualPath?: string }> {
  if (!currentSite.value?.siteId || !filePath) return { exists: false };
  try {
    // 复用已加载的文件列表，或重新加载
    let files = aiPackageFiles.value;
    if (files.length === 0) {
      files = await listUiPackageFiles(currentSite.value.siteId);
    }

    // 精确匹配或忽略前导斜杠匹配
    const exactMatch = files.find(f =>
      f.relativePath === filePath ||
      f.relativePath === '/' + filePath ||
      f.relativePath.replace(/^\/+/, '') === filePath
    );

    if (exactMatch) {
      return { exists: true, actualPath: exactMatch.relativePath };
    }

    // 智能匹配：同名但扩展名不同（.html/.htm/.aspx/.asp 互换）
    const basePath = filePath.replace(/\.(html?|aspx?)$/i, '');
    const altMatch = files.find(f => {
      const normalizedPath = f.relativePath.replace(/^\/+/, '');
      const normalizedBase = normalizedPath.replace(/\.(html?|aspx?)$/i, '');
      return normalizedBase === basePath && /\.(html?|aspx?)$/i.test(normalizedPath);
    });

    if (altMatch) {
      console.log('[checkFileExists] 智能匹配成功:', filePath, '->', altMatch.relativePath);
      return { exists: true, actualPath: altMatch.relativePath };
    }

    return { exists: false };
  } catch (e) {
    console.error('检查文件存在失败:', e);
    return { exists: false };
  }
}

/**
 * 打开 AI 生成器（编辑模式）
 * @param type 'list' | 'detail' - 列表页或详情页
 */
async function openAiGenerator(type: 'list' | 'detail') {
  if (!currentSite.value?.siteId) {
    ElMessage.warning('请先选择站点');
    return;
  }

  // 根据类型获取地址字段
  const pageUrl = type === 'list' ? form.listPageUrl : form.detailPageUrl;
  const filePath = extractFilePath(pageUrl);

  if (filePath) {
    // 检查文件是否存在
    const result = await checkFileExists(filePath);
    if (result.exists && result.actualPath) {
      // 直接跳转到预览页面（使用实际存在的文件路径，并传递栏目ID和模板类型）
      router.push({
        path: '/admin/v2/cms/ui-package-preview',
        query: {
          siteId: String(currentSite.value.siteId),
          path: result.actualPath,
          from: 'menu-edit',
          columnId: form.id ? String(form.id) : undefined,
          templateType: type
        }
      });
      return;
    }
  }

  // 文件不存在或地址为空，打开选择器
  ElMessage.info('请选择要使用的模板文件');
  selectedAiGenType.value = type;
  selectedAiGenForm.value = 'edit';
  aiEntrySource.value = 'menu-edit';
  await openAiRecognizeDialog();
  // openAiRecognizeDialog 会重置 aiEntrySource，需要在调用后重新设置
  aiEntrySource.value = 'menu-edit';
}

function openSelectedFile() {
  if (!currentSite.value?.siteId || !selectedFilePath.value) {
    return;
  }

  const query: Record<string, string> = {
    siteId: String(currentSite.value.siteId),
    path: selectedFilePath.value,
    from: aiEntrySource.value,
    templateType: selectedAiGenType.value
  };

  // 编辑模式下传递 columnId
  if (selectedAiGenForm.value === 'edit' && form.id) {
    query.columnId = String(form.id);
  }

  // 跳转到预览页面
  router.push({
    path: '/admin/v2/cms/ui-package-preview',
    query
  });
}
</script>

<style scoped>
.cms-page {
  display: grid;
  gap: var(--admin-card-gap);
}

.cms-card {
  border: var(--admin-card-border);
  border-radius: var(--admin-card-radius);
  background: var(--admin-surface);
  box-shadow: var(--admin-shadow-card);
}

.cms-card__header h2,
.cms-card__header p {
  margin: 0;
}

.cms-card__header p {
  color: var(--admin-text-muted);
  font-size: var(--admin-font-size-base, 14px);
}

.cms-card__header h2 {
  font-size: var(--admin-font-size-section, 16px);
}

.cms-card__header,
.cms-form__footer,
.cms-form__switches {
  display: flex;
  align-items: center;
  gap: 12px;
}

.cms-card {
  padding: var(--admin-card-padding);
}

.cms-card__header {
  justify-content: space-between;
  margin-bottom: 18px;
}

.cms-menus__layout {
  display: grid;
  grid-template-columns: minmax(var(--admin-tree-min-width), var(--column-navigation-width, var(--admin-tree-width))) var(--admin-tree-toggle-rail-width) minmax(0, 1fr);
  gap: 0;
  min-width: 0;
}

.cms-menus__tree,
.cms-menus__main,
.cms-menus__tabs,
.cms-menus__tab-content {
  min-width: 0;
}

.cms-menus__layout.is-tree-collapsed {
  grid-template-columns: var(--admin-tree-toggle-rail-width) minmax(0, 1fr);
}

.cms-menus__tree-panel {
  position: relative;
  display: grid;
  min-width: 0;
  min-height: 0;
}

.cms-menus__tree .cms-card__header {
  flex-wrap: wrap;
}

.cms-menus__tree-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.cms-menus__tree {
  min-height: 560px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.cms-menus__tree > .cms-card__header {
  flex: 0 0 auto;
}

.cms-menus__tree :deep(.el-tree) {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
}

/* Element Plus 给 .el-tree-node > .el-tree-node__children 设了 overflow: hidden，会裁掉多级
   （嵌套）节点的横向溢出，导致只有顶层节点能撑出横向滚动、子级长名称被裁。
   而且展开/收起的折叠过渡会用“内联” overflow: hidden，内联优先级高于普通规则，所以这里必须
   用 !important 才能放开裁剪；动画期间用更具体的 .el-collapse-transition-*-active 规则重新
   裁剪，保证折叠动画不变形。 */
.cms-menus__tree :deep(.el-tree-node__children) {
  overflow: visible !important;
}

.cms-menus__tree :deep(.el-tree-node__children.el-collapse-transition-enter-active),
.cms-menus__tree :deep(.el-tree-node__children.el-collapse-transition-leave-active) {
  overflow: hidden !important;
}

/* 强制横向滚动条常显：::-webkit-scrollbar 的 height 会把系统浮层滚动条转为经典滚动条，
   否则 Windows/Chromium 的“自动隐藏滚动条”会让静止时的滚动条不可见。
   注意：不要同时设置 scrollbar-width/scrollbar-color，否则会令下面的 ::-webkit-scrollbar 失效。 */
.cms-menus__tree :deep(.el-tree)::-webkit-scrollbar {
  width: 10px;
  height: 10px;
}

.cms-menus__tree :deep(.el-tree)::-webkit-scrollbar-track {
  background: transparent;
}

.cms-menus__tree :deep(.el-tree)::-webkit-scrollbar-thumb {
  border-radius: 5px;
  background: #c1c1c1;
}

.cms-menus__tree :deep(.el-tree)::-webkit-scrollbar-thumb:hover {
  background: #a8a8a8;
}

.cms-menus__main {
  display: flex;
  flex-direction: column;
  background: var(--admin-surface);
  border: var(--admin-card-border);
  border-radius: var(--admin-card-radius);
  box-shadow: var(--admin-shadow-card);
}

/* 宽屏：整页占满视口，栏目导航默认全屏高度——树在面板内部滚动（横/纵），
   右侧主内容列也各自内部滚动，页面本身不再整体滚动。 */
@media (min-width: 1181px) {
  .cms-page {
    flex: 1;
    min-height: 0;
    grid-template-rows: minmax(0, 1fr);
  }

  .cms-menus__layout {
    min-height: 0;
  }

  .cms-menus__tree {
    min-height: 0;
  }

  .cms-menus__main {
    overflow: auto;
    min-height: 0;
  }
}

.cms-menus__toolbar {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-light);
}

.cms-menus__toolbar-info {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.cms-menus__site-context {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--admin-text-muted);
  font-size: var(--admin-font-size-small);
}

.cms-menus__toolbar-actions {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 4px;
  margin-left: auto;
}

.cms-menus__toolbar-actions .el-button + .el-button {
  margin-left: 0;
}

.cms-form__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.cms-form__inline-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.cms-form__inline-item {
  margin-bottom: 0;
}

.cms-form__field-hint {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.8;
}

.cms-form__field-hint-item {
  margin-right: 10px;
  white-space: nowrap;
}

.cms-form__var {
  margin-right: 2px;
  padding: 0 3px;
  border-radius: 3px;
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
  font-family: 'Consolas', 'Monaco', monospace;
  cursor: pointer;
  user-select: none;
}

.cms-form__footer {
  justify-content: flex-end;
  margin-top: 16px;
}

@media (max-width: 1180px) {
  .cms-menus__layout,
  .cms-form__grid {
    grid-template-columns: 1fr;
  }
}

.cms-menus__tree-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.ai-dialog-loading,
.ai-dialog-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px;
  color: var(--el-text-color-secondary);
}

.ai-file-browser {
  display: flex;
  height: 500px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  overflow: hidden;
}

.ai-file-sidebar {
  width: 220px;
  border-right: 1px solid var(--el-border-color);
  background: var(--el-fill-color-lighter);
  display: flex;
  flex-direction: column;
}

.ai-sidebar-header {
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color);
  font-weight: 600;
  font-size: 14px;
}

.ai-sidebar-header h4 {
  margin: 0;
}

.ai-file-sidebar .el-tree {
  flex: 1;
  overflow: auto;
  padding: 8px;
  background: transparent;
}

.ai-tree-node {
  display: flex;
  align-items: center;
  gap: 6px;
}

.ai-tree-icon {
  font-size: 14px;
}

.ai-file-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.ai-breadcrumb {
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color);
  background: var(--el-bg-color);
}

.ai-breadcrumb .el-breadcrumb-item {
  cursor: pointer;
}

.ai-file-list {
  flex: 1;
  overflow: auto;
  padding: 8px;
}

.ai-file-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.ai-file-item:hover {
  background: var(--el-fill-color-light);
}

.ai-file-item.is-selected {
  background: var(--el-color-primary-light-9);
}

.ai-file-icon {
  font-size: 18px;
  margin-right: 10px;
}

.ai-file-name {
  flex: 1;
  font-size: 14px;
}

.ai-file-action {
  opacity: 0;
  transition: opacity 0.2s;
}

.ai-file-item:hover .ai-file-action {
  opacity: 1;
}

.ai-drawer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color);
  margin-top: 16px;
}

.ai-selected-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.ai-gen-btn {
  font-size: 12px;
}

.cms-menus__tabs {
  flex-shrink: 0;
}

.cms-menus__tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 20px;
  border-bottom: 1px solid var(--el-border-color-light);
}

.cms-menus__current-menu {
  flex-shrink: 0;
  max-width: 50%;
  min-width: 0;
}

.cms-menus__current-menu :deep(.el-tag__content) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cms-menus__tabs :deep(.el-tabs__content) {
  padding: 20px;
  min-width: 0;
}

.cms-menus__edit-pane {
  display: grid;
  gap: var(--admin-card-gap);
}

.cms-menus__tab-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 300px;
}

.cms-menus__tab-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.cms-menus__page-type-selector {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.cms-menus__page-type-selector span {
  color: var(--admin-text-muted);
  font-size: 14px;
}

.cms-menus__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px;
  color: var(--el-text-color-secondary);
}

.cms-menus__field-presets {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 16px 0;
}

.cms-menus__field-advanced {
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  overflow: hidden;
}

.cms-menus__field-advanced-toggle,
.cms-menus__field-filter-header {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  border: 0;
  color: inherit;
  cursor: pointer;
  text-align: left;
}

.cms-menus__field-advanced-toggle {
  padding: 12px 16px;
  background: var(--el-fill-color-lighter);
  font-size: var(--admin-font-size-base, 14px);
  font-weight: 600;
}

.cms-menus__field-advanced-toggle:hover,
.cms-menus__field-advanced-toggle:focus-visible {
  background: var(--el-fill-color-light);
  outline: none;
}

.cms-menus__field-advanced-toggle .el-icon {
  margin-left: auto;
  transition: transform 0.3s;
}

.cms-menus__field-advanced-toggle .el-icon.is-expanded {
  transform: rotate(180deg);
}

.cms-menus__field-advanced-content {
  border-top: 1px solid var(--el-border-color-light);
}

.cms-menus__field-filter {
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  overflow: hidden;
}

.cms-menus__field-filter-header {
  padding: 12px 16px;
  background: var(--el-fill-color-lighter);
}

.cms-menus__field-filter-header:hover,
.cms-menus__field-filter-header:focus-visible {
  background: var(--el-fill-color-light);
  outline: none;
}

.cms-menus__field-filter-header span:first-child {
  font-weight: 500;
}

.cms-menus__field-filter-count {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-small, 13px);
}

.cms-menus__field-filter-header .el-icon {
  margin-left: auto;
  transition: transform 0.3s;
}

.cms-menus__field-filter-header .el-icon.is-expanded {
  transform: rotate(180deg);
}

.cms-menus__field-filter-content {
  padding: 16px;
  max-height: 500px;
  overflow-y: auto;
}

/* 字段分组折叠样式 */
.cms-menus__field-collapse {
  border: none;
}

.cms-menus__field-collapse :deep(.el-collapse-item__header) {
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 0 12px;
  height: 40px;
  line-height: 40px;
  font-weight: 500;
  margin-bottom: 8px;
}

.cms-menus__field-collapse :deep(.el-collapse-item__wrap) {
  border: none;
  background: transparent;
}

.cms-menus__field-collapse :deep(.el-collapse-item__content) {
  padding: 0;
  padding-bottom: 8px;
}

.cms-menus__field-group-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.cms-menus__field-group-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-weight: normal;
  margin-right: 8px;
}

.cms-menus__field-group-items {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  padding: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  background: var(--el-bg-color);
}

.cms-menus__field-group-items .el-checkbox {
  margin-right: 0;
}

.cms-menus__field-table {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
}

.cms-menus__field-grid {
  width: 100%;
  min-width: 0;
}

.cms-menus__field-footer {
  margin-top: 0;
}

.cms-menus__input-type {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: nowrap;
}
.cms-menus__input-type .el-select {
  flex-shrink: 0;
}

.cms-menus__dialog-hint {
  color: var(--admin-text-muted);
  font-size: 12px;
  margin-bottom: 12px;
}

.cms-menus__enum-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.cms-menus__menu-picker {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 420px;
  overflow: hidden;
}

.cms-menus__menu-picker :deep(.el-tree) {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
}

.cms-menus__menu-picker-filter {
  margin-bottom: 4px;
}

.cms-menus__menu-picker-selected {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.cms-menus__menu-picker-selected-label,
.cms-menus__menu-picker-selected-empty {
  color: var(--admin-text-muted);
  font-size: var(--admin-font-size-small);
}

.cms-menus__cascade-ref {
  margin-bottom: 8px;
  padding: 8px 12px;
  border: 1px dashed var(--el-color-primary-light-5);
  border-radius: 4px;
  background: var(--el-color-primary-light-9);
  font-size: var(--admin-font-size-small);
}

.cms-menus__cascade-ref-title {
  color: var(--el-color-primary);
  font-weight: 600;
  margin-bottom: 4px;
}

.cms-menus__cascade-ref-list {
  margin: 0;
  padding-left: 18px;
  color: var(--admin-text-muted);
  line-height: 1.7;
}

.cms-menus__menu-node.is-selected {
  color: var(--el-color-primary);
  font-weight: 600;
}

.cms-form {
  padding: 0 4px;
}

.cms-form__switches {
  margin-bottom: 16px;
}

.menu-setting { width: 100%; }
.menu-setting__ops {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  padding: 12px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: 6px;
  background: #fafafa;
  margin-bottom: 10px;
}
.menu-setting__ops .el-checkbox { margin-right: 0; white-space: nowrap; }
.menu-setting__quick { display: flex; flex-wrap: wrap; gap: 8px; }

.cms-menus__tree-node {
  display: flex;
  align-items: center;
  gap: 6px;
}

.cms-menus__tree :deep(.el-tree-node__content) {
  width: max-content;
  min-width: 100%;
}

.cms-menus__tree-node-name {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cms-menus__tree-node :deep(.el-tag) {
  flex: 0 0 auto;
}

.cms-menus__tree-node.is-denied {
  opacity: 0.45;
  pointer-events: none;
}

@media (max-width: 1180px) {
  .cms-menus__layout {
    grid-template-columns: var(--admin-tree-toggle-rail-width) minmax(0, 1fr);
    row-gap: var(--admin-card-gap);
  }

  .cms-menus__tree-panel {
    grid-column: 2;
    grid-row: 1;
  }

  .cms-menus__main {
    grid-column: 2;
    grid-row: 2;
  }

  .cms-menus__tree-toggle {
    grid-column: 1;
    grid-row: 1 / 3;
  }

  .cms-menus__layout.is-tree-collapsed > .cms-menus__main,
  .cms-menus__layout.is-tree-collapsed > .cms-menus__tree-toggle {
    grid-row: 1;
  }
}
</style>
