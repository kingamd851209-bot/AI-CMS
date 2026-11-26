<template>
  <section class="headless-api-doc">
    <header class="headless-api-doc__header">
      <div>
        <p class="admin-eyebrow">Headless API</p>
        <h1>接口文档说明</h1>
      </div>
      <p class="headless-api-doc__subtitle">服务接口规范</p>
    </header>

    <aside class="headless-api-doc__toc">
      <nav class="toc-nav">
        <h3>目录</h3>
        <ul>
          <li><a href="#section-base" @click.prevent="scrollTo('section-base')">基础信息</a></li>
          <li><a href="#section-menu" @click.prevent="scrollTo('section-menu')">栏目接口</a></li>
          <li><a href="#section-content" @click.prevent="scrollTo('section-content')">内容接口</a></li>
          <li><a href="#section-search" @click.prevent="scrollTo('section-search')">搜索接口</a></li>
          <li><a href="#section-banner" @click.prevent="scrollTo('section-banner')">Banner接口</a></li>
          <li><a href="#section-date" @click.prevent="scrollTo('section-date')">日期格式化接口</a></li>
          <li><a href="#section-utils" @click.prevent="scrollTo('section-utils')">工具接口</a></li>
          <li><a href="#section-pager" @click.prevent="scrollTo('section-pager')">分页响应格式</a></li>
          <li><a href="#section-error" @click.prevent="scrollTo('section-error')">错误响应</a></li>
        </ul>
      </nav>
    </aside>

    <article id="section-base" class="headless-api-doc__section">
      <dl class="api-spec">
        <dt>Base URL</dt>
        <dd><code>/api/delivery/v1/sites/{siteCode}</code></dd>
        <dt>认证方式</dt>
        <dd>API Key 或 Bearer Token（生产环境必须启用）</dd>
        <dt>响应格式</dt>
        <dd>application/json</dd>
      </dl>
    </article>

    <article id="section-menu" class="headless-api-doc__section">
      <h2>栏目接口</h2>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/{menuId}</code>
        </div>
        <p class="api-endpoint__desc">根据ID获取栏目详情</p>
        <p class="api-endpoint__source">对应方法：getOneshotEntityById(id)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>menuId</td>
              <td>integer</td>
              <td>是</td>
              <td>栏目ID</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/by-parent/{parentId}</code>
        </div>
        <p class="api-endpoint__desc">根据父栏目ID获取子栏目列表</p>
        <p class="api-endpoint__source">对应方法：getOneshotListByParentId(parentId)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>parentId</td>
              <td>integer</td>
              <td>是</td>
              <td>父栏目ID</td>
            </tr>
            <tr>
              <td>limit</td>
              <td>integer</td>
              <td>否</td>
              <td>返回数量限制，默认100</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/by-parent/{parentId}/exclude</code>
        </div>
        <p class="api-endpoint__desc">根据父栏目ID获取子栏目列表（排除指定栏目）</p>
        <p class="api-endpoint__source">对应方法：getOneshotListByParentId(parentId, outClassId)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>parentId</td>
              <td>integer</td>
              <td>是</td>
              <td>父栏目ID</td>
            </tr>
            <tr>
              <td>outClassId</td>
              <td>string</td>
              <td>否</td>
              <td>排除的栏目ID，逗号分隔</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/by-identifies</code>
        </div>
        <p class="api-endpoint__desc">根据父栏目ID和唯一标识获取栏目</p>
        <p class="api-endpoint__source">对应方法：getOneshotEntityByParentIdAndIdentifies(parentId, identifies)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>parentId</td>
              <td>integer</td>
              <td>是</td>
              <td>父栏目ID</td>
            </tr>
            <tr>
              <td>identifies</td>
              <td>string</td>
              <td>是</td>
              <td>栏目唯一标识</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/by-identifies-list</code>
        </div>
        <p class="api-endpoint__desc">根据父栏目ID和多个唯一标识获取栏目列表</p>
        <p class="api-endpoint__source">对应方法：getOneshotListByParentIdAndIdentifies(parentId, identifies...)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>parentId</td>
              <td>integer</td>
              <td>是</td>
              <td>父栏目ID</td>
            </tr>
            <tr>
              <td>identifies</td>
              <td>string</td>
              <td>是</td>
              <td>栏目唯一标识，逗号分隔多个</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/by-show-position</code>
        </div>
        <p class="api-endpoint__desc">根据父栏目ID和显示位置获取栏目列表</p>
        <p class="api-endpoint__source">对应方法：getOneshotListByParentIdAndShowPosition(parentId, showPosition)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>parentId</td>
              <td>integer</td>
              <td>是</td>
              <td>父栏目ID</td>
            </tr>
            <tr>
              <td>showPosition</td>
              <td>string</td>
              <td>是</td>
              <td>显示位置标识</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/by-ids</code>
        </div>
        <p class="api-endpoint__desc">根据ID集合批量获取栏目</p>
        <p class="api-endpoint__source">对应方法：getOneshotListByIds(ids)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>ids</td>
              <td>string</td>
              <td>是</td>
              <td>栏目ID列表，逗号分隔，如：1,2,3</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/{menuId}/breadcrumb</code>
        </div>
        <p class="api-endpoint__desc">获取面包屑导航</p>
        <p class="api-endpoint__source">对应方法：getBreadcrumb(classId)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>menuId</td>
              <td>integer</td>
              <td>是</td>
              <td>栏目ID</td>
            </tr>
            <tr>
              <td>contentId</td>
              <td>integer</td>
              <td>否</td>
              <td>内容ID，可选</td>
            </tr>
          </tbody>
        </table>
        <h4>响应示例</h4>
        <pre class="api-code">[
  { "id": 1, "name": "首页", "url": "/" },
  { "id": 10, "name": "新闻中心", "url": "/news" },
  { "id": 100, "name": "公司新闻", "url": "/news/100" }
]</pre>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/{menuId}/first-child</code>
        </div>
        <p class="api-endpoint__desc">获取栏目下第一个子栏目ID</p>
        <p class="api-endpoint__source">对应方法：getFirstOneshotListByParentId(parentId)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>menuId</td>
              <td>integer</td>
              <td>是</td>
              <td>栏目ID</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/class-ids-by-identifies</code>
        </div>
        <p class="api-endpoint__desc">根据唯一标识获取栏目ID集合</p>
        <p class="api-endpoint__source">对应方法：getClassIdListByIdentifies(identifies)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>identifies</td>
              <td>string</td>
              <td>是</td>
              <td>栏目唯一标识</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/{menuId}/url</code>
        </div>
        <p class="api-endpoint__desc">获取栏目页地址</p>
        <p class="api-endpoint__source">对应方法：getListUrl(oneshotEntity)</p>
        <h4>响应示例</h4>
        <pre class="api-code">{
  "url": "/news/list/1"
}</pre>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/random-product</code>
        </div>
        <p class="api-endpoint__desc">获取随机推荐产品栏目</p>
        <p class="api-endpoint__source">对应方法：getRandomProduct()</p>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/companies-by-business/{businessId}</code>
        </div>
        <p class="api-endpoint__desc">根据业务分类ID获取公司列表</p>
        <p class="api-endpoint__source">对应方法：getCompanyListByBusinessId(businessId)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>businessId</td>
              <td>integer</td>
              <td>是</td>
              <td>业务分类ID</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/child-companies/{categoryId}</code>
        </div>
        <p class="api-endpoint__desc">根据分类ID获取子公司列表</p>
        <p class="api-endpoint__source">对应方法：getChildCompany(categoryId)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>categoryId</td>
              <td>integer</td>
              <td>是</td>
              <td>分类ID</td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>

    <article id="section-content" class="headless-api-doc__section">
      <h2>内容接口</h2>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/contents/{contentId}</code>
        </div>
        <p class="api-endpoint__desc">根据内容ID获取内容详情</p>
        <p class="api-endpoint__source">对应方法：getContentEntityById(id)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>contentId</td>
              <td>integer</td>
              <td>是</td>
              <td>内容ID</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/contents/{contentId}/by-menu/{menuId}</code>
        </div>
        <p class="api-endpoint__desc">根据栏目ID和内容ID获取内容详情</p>
        <p class="api-endpoint__source">对应方法：getContentEntityById(classId, id)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>menuId</td>
              <td>integer</td>
              <td>是</td>
              <td>栏目ID</td>
            </tr>
            <tr>
              <td>contentId</td>
              <td>integer</td>
              <td>是</td>
              <td>内容ID</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/{menuId}/contents</code>
        </div>
        <p class="api-endpoint__desc">根据栏目ID获取内容列表</p>
        <p class="api-endpoint__source">对应方法：getContentListByClassId(classId, limit, setting, pageIndex)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>menuId</td>
              <td>integer</td>
              <td>是</td>
              <td>栏目ID</td>
            </tr>
            <tr>
              <td>limit</td>
              <td>integer</td>
              <td>否</td>
              <td>返回数量，默认100</td>
            </tr>
            <tr>
              <td>setting</td>
              <td>integer</td>
              <td>否</td>
              <td>内容属性：8首页置顶、9首页热门、10首页推荐、11频道置顶、12频道热门、13频道推荐、14栏目置顶、15栏目热门、16栏目推荐、17导航推荐、18右侧推荐、19左侧推荐</td>
            </tr>
            <tr>
              <td>page</td>
              <td>integer</td>
              <td>否</td>
              <td>页码，默认1</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/{menuId}/contents/pager</code>
        </div>
        <p class="api-endpoint__desc">根据栏目ID获取分页内容列表</p>
        <p class="api-endpoint__source">对应方法：getContentPagerByClassId(idEntity)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>menuId</td>
              <td>integer</td>
              <td>是</td>
              <td>栏目ID</td>
            </tr>
            <tr>
              <td>page</td>
              <td>integer</td>
              <td>否</td>
              <td>页码，默认1</td>
            </tr>
            <tr>
              <td>size</td>
              <td>integer</td>
              <td>否</td>
              <td>每页数量，默认20</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/class-ids contents/pager</code>
        </div>
        <p class="api-endpoint__desc">根据多个栏目ID获取分页内容列表</p>
        <p class="api-endpoint__source">对应方法：getContentPagerByClassIds(classIds, idEntity)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>classIds</td>
              <td>string</td>
              <td>是</td>
              <td>栏目ID列表，逗号分隔</td>
            </tr>
            <tr>
              <td>page</td>
              <td>integer</td>
              <td>否</td>
              <td>页码，默认1</td>
            </tr>
            <tr>
              <td>size</td>
              <td>integer</td>
              <td>否</td>
              <td>每页数量，默认20</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/{parentId}/contents</code>
        </div>
        <p class="api-endpoint__desc">根据父栏目ID获取内容列表（包含所有子栏目内容）</p>
        <p class="api-endpoint__source">对应方法：getContentListByParentId(parentId, limit, setting, outClassIds)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>parentId</td>
              <td>integer</td>
              <td>是</td>
              <td>父栏目ID</td>
            </tr>
            <tr>
              <td>limit</td>
              <td>integer</td>
              <td>否</td>
              <td>返回数量，默认100</td>
            </tr>
            <tr>
              <td>setting</td>
              <td>integer</td>
              <td>否</td>
              <td>内容属性：8-19</td>
            </tr>
            <tr>
              <td>outClassIds</td>
              <td>string</td>
              <td>否</td>
              <td>排除的栏目ID，逗号分隔</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/{parentId}/contents/pager</code>
        </div>
        <p class="api-endpoint__desc">根据父栏目ID获取分页内容列表</p>
        <p class="api-endpoint__source">对应方法：getContentPagerByParentId(idEntity)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>parentId</td>
              <td>integer</td>
              <td>是</td>
              <td>父栏目ID</td>
            </tr>
            <tr>
              <td>page</td>
              <td>integer</td>
              <td>否</td>
              <td>页码，默认1</td>
            </tr>
            <tr>
              <td>size</td>
              <td>integer</td>
              <td>否</td>
              <td>每页数量，默认20</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/contents/by-parent-identifies</code>
        </div>
        <p class="api-endpoint__desc">根据父栏目ID和唯一标识获取内容列表</p>
        <p class="api-endpoint__source">对应方法：getContentByParentIdAndIdentifies(parentId, identifies, size)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>parentId</td>
              <td>integer</td>
              <td>是</td>
              <td>父栏目ID</td>
            </tr>
            <tr>
              <td>identifies</td>
              <td>string</td>
              <td>是</td>
              <td>栏目唯一标识</td>
            </tr>
            <tr>
              <td>size</td>
              <td>integer</td>
              <td>否</td>
              <td>返回数量，默认100</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/contents/by-show-position</code>
        </div>
        <p class="api-endpoint__desc">根据父栏目ID和显示位置获取内容列表</p>
        <p class="api-endpoint__source">对应方法：getContentListByParentIdAndShowPosition(parentId, showPosition, limit)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>parentId</td>
              <td>integer</td>
              <td>是</td>
              <td>父栏目ID</td>
            </tr>
            <tr>
              <td>showPosition</td>
              <td>string</td>
              <td>是</td>
              <td>显示位置标识</td>
            </tr>
            <tr>
              <td>limit</td>
              <td>integer</td>
              <td>否</td>
              <td>返回数量，默认100</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/{menuId}/contents/hot</code>
        </div>
        <p class="api-endpoint__desc">获取热门内容（按浏览量排序）</p>
        <p class="api-endpoint__source">对应方法：getContentHotListByClassId(classId, limit)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>menuId</td>
              <td>integer</td>
              <td>是</td>
              <td>栏目ID</td>
            </tr>
            <tr>
              <td>limit</td>
              <td>integer</td>
              <td>否</td>
              <td>返回数量，默认10</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/{parentId}/contents/hot</code>
        </div>
        <p class="api-endpoint__desc">根据父栏目ID获取热门内容</p>
        <p class="api-endpoint__source">对应方法：getContentHotListByParentId(parentId, limit)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>parentId</td>
              <td>integer</td>
              <td>是</td>
              <td>父栏目ID</td>
            </tr>
            <tr>
              <td>limit</td>
              <td>integer</td>
              <td>否</td>
              <td>返回数量，默认10</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/contents/{contentId}/prev-next</code>
        </div>
        <p class="api-endpoint__desc">获取上一篇和下一篇内容</p>
        <p class="api-endpoint__source">对应方法：getPrevAndNextByIdEntity(idEntity)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>contentId</td>
              <td>integer</td>
              <td>是</td>
              <td>当前内容ID</td>
            </tr>
            <tr>
              <td>menuId</td>
              <td>integer</td>
              <td>否</td>
              <td>栏目ID</td>
            </tr>
          </tbody>
        </table>
        <h4>响应示例</h4>
        <pre class="api-code">{
  "prev": { "id": 99, "title": "上一篇标题", "url": "/news/99" },
  "next": { "id": 101, "title": "下一篇标题", "url": "/news/101" }
}</pre>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/contents/by-ids</code>
        </div>
        <p class="api-endpoint__desc">根据ID集合批量获取内容</p>
        <p class="api-endpoint__source">对应方法：getContentListByIds(ids)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>ids</td>
              <td>string</td>
              <td>是</td>
              <td>内容ID列表，逗号分隔，如：1,2,3</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/contents/by-relation/{relationId}</code>
        </div>
        <p class="api-endpoint__desc">根据关联ID获取关联内容</p>
        <p class="api-endpoint__source">对应方法：getContentListByRelationId(relationId)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>relationId</td>
              <td>integer</td>
              <td>是</td>
              <td>关联ID</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/contents/by-menu-relation</code>
        </div>
        <p class="api-endpoint__desc">根据栏目ID和关联ID获取关联内容</p>
        <p class="api-endpoint__source">对应方法：getContentListByClassIdAndRelationId(classId, relationId)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>menuId</td>
              <td>integer</td>
              <td>否</td>
              <td>栏目ID</td>
            </tr>
            <tr>
              <td>relationId</td>
              <td>integer</td>
              <td>是</td>
              <td>关联ID</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/menus/{menuId}/contents/unpublished</code>
        </div>
        <p class="api-endpoint__desc">获取栏目未发布的内容列表</p>
        <p class="api-endpoint__source">对应方法：getContentListNoPublishedByClassId(classId)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>menuId</td>
              <td>integer</td>
              <td>是</td>
              <td>栏目ID</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/contents/{contentId}/url</code>
        </div>
        <p class="api-endpoint__desc">获取内容详情页地址</p>
        <p class="api-endpoint__source">对应方法：getDetailUrl(contentEntity)</p>
        <h4>响应示例</h4>
        <pre class="api-code">{
  "url": "/news/detail/123",
  "type": "content"
}</pre>
        <p class="api-note">说明：如果内容有外链则返回外链地址，否则返回内容详情页地址</p>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/contents/{contentId}/common-url</code>
        </div>
        <p class="api-endpoint__desc">获取内容基础详情页地址</p>
        <p class="api-endpoint__source">对应方法：getDetailCommonUrl(contentEntity)</p>
        <h4>响应示例</h4>
        <pre class="api-code">{
  "url": "/news/detail/123"
}</pre>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/contents/{contentId}/title</code>
        </div>
        <p class="api-endpoint__desc">根据内容ID获取标题</p>
        <p class="api-endpoint__source">对应方法：getTitleById(id)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>contentId</td>
              <td>integer</td>
              <td>是</td>
              <td>内容ID</td>
            </tr>
          </tbody>
        </table>
        <h4>响应示例</h4>
        <pre class="api-code">{
  "title": "文章标题"
}</pre>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/contents/group-by-title</code>
        </div>
        <p class="api-endpoint__desc">根据条件按标题分组</p>
        <p class="api-endpoint__source">对应方法：getGroupByTitleByIdEntity(idEntity, addAll)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>menuId</td>
              <td>integer</td>
              <td>否</td>
              <td>栏目ID</td>
            </tr>
            <tr>
              <td>addAll</td>
              <td>boolean</td>
              <td>否</td>
              <td>是否添加全部选项，默认false</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/contents/field-group</code>
        </div>
        <p class="api-endpoint__desc">根据栏目ID获取字段分组数据</p>
        <p class="api-endpoint__source">对应方法：getContentFieldByClassIdGroupBy(classId, fieldName, limit, isGroup, sortType)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>menuId</td>
              <td>integer</td>
              <td>是</td>
              <td>栏目ID</td>
            </tr>
            <tr>
              <td>fieldName</td>
              <td>string</td>
              <td>是</td>
              <td>字段名</td>
            </tr>
            <tr>
              <td>limit</td>
              <td>integer</td>
              <td>否</td>
              <td>返回数量，默认100</td>
            </tr>
            <tr>
              <td>isGroup</td>
              <td>boolean</td>
              <td>否</td>
              <td>是否按字段分组排序</td>
            </tr>
            <tr>
              <td>sortType</td>
              <td>string</td>
              <td>否</td>
              <td>排序类型：desc或asc</td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>

    <article id="section-search" class="headless-api-doc__section">
      <h2>搜索接口</h2>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/search</code>
        </div>
        <p class="api-endpoint__desc">全文搜索内容</p>
        <p class="api-endpoint__source">对应方法：getContentPagerByKey(idEntity)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>keyword</td>
              <td>string</td>
              <td>是</td>
              <td>搜索关键词</td>
            </tr>
            <tr>
              <td>page</td>
              <td>integer</td>
              <td>否</td>
              <td>页码，默认1</td>
            </tr>
            <tr>
              <td>size</td>
              <td>integer</td>
              <td>否</td>
              <td>每页数量，默认20</td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>

    <article id="section-banner" class="headless-api-doc__section">
      <h2>Banner接口</h2>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/banners/{bannerId}</code>
        </div>
        <p class="api-endpoint__desc">获取Banner对象</p>
        <p class="api-endpoint__source">对应方法：getBannerEntity(bannerId)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>bannerId</td>
              <td>integer</td>
              <td>是</td>
              <td>Banner栏目ID</td>
            </tr>
          </tbody>
        </table>
        <h4>响应示例</h4>
        <pre class="api-code">{
  "bannerTitle": "Banner标题",
  "bannerNote": "Banner描述",
  "bannerPhoto": "图片地址"
}</pre>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/banners/business/{bannerId}</code>
        </div>
        <p class="api-endpoint__desc">获取业务板块Banner对象</p>
        <p class="api-endpoint__source">对应方法：getBusinessBannerEntity(bannerId)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>bannerId</td>
              <td>integer</td>
              <td>是</td>
              <td>Banner内容ID</td>
            </tr>
          </tbody>
        </table>
        <h4>响应示例</h4>
        <pre class="api-code">{
  "bannerTitle": "业务标题",
  "bannerNote": "业务描述",
  "bannerPhoto": "图片地址"
}</pre>
      </div>
    </article>

    <article id="section-date" class="headless-api-doc__section">
      <h2>日期格式化接口</h2>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/utils/format-date</code>
        </div>
        <p class="api-endpoint__desc">格式化日期</p>
        <p class="api-endpoint__source">对应方法：getDateString(date, pattern)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>date</td>
              <td>string</td>
              <td>是</td>
              <td>日期字符串或时间戳</td>
            </tr>
            <tr>
              <td>pattern</td>
              <td>string</td>
              <td>否</td>
              <td>格式模式，默认yyyy-MM-dd</td>
            </tr>
          </tbody>
        </table>
        <h4>响应示例</h4>
        <pre class="api-code">{
  "formatted": "2026-07-10"
}</pre>
      </div>
    </article>

    <article id="section-utils" class="headless-api-doc__section">
      <h2>工具接口</h2>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--post">POST</span>
          <code>/utils/mask-string</code>
        </div>
        <p class="api-endpoint__desc">字符脱敏处理</p>
        <p class="api-endpoint__source">对应方法：getSomeStars(str, beginLength, endLength)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>str</td>
              <td>string</td>
              <td>是</td>
              <td>原始字符串</td>
            </tr>
            <tr>
              <td>beginLength</td>
              <td>integer</td>
              <td>是</td>
              <td>保留前多少位</td>
            </tr>
            <tr>
              <td>endLength</td>
              <td>integer</td>
              <td>是</td>
              <td>保留后多少位</td>
            </tr>
            <tr>
              <td>starMaxCount</td>
              <td>integer</td>
              <td>否</td>
              <td>最多显示星号数量</td>
            </tr>
          </tbody>
        </table>
        <h4>响应示例</h4>
        <pre class="api-code">{
  "masked": "138****5678"
}</pre>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--post">POST</span>
          <code>/utils/to-json</code>
        </div>
        <p class="api-endpoint__desc">对象转JSON字符串</p>
        <p class="api-endpoint__source">对应方法：toJsonString(obj)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>object</td>
              <td>object</td>
              <td>是</td>
              <td>任意对象</td>
            </tr>
          </tbody>
        </table>
        <h4>响应示例</h4>
        <pre class="api-code">{
  "json": "{\"key\": \"value\"}"
}</pre>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--post">POST</span>
          <code>/utils/check-collection</code>
        </div>
        <p class="api-endpoint__desc">检查集合是否非空</p>
        <p class="api-endpoint__source">对应方法：isNotEmptyCollection(coll)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>collection</td>
              <td>array</td>
              <td>是</td>
              <td>集合数据</td>
            </tr>
          </tbody>
        </table>
        <h4>响应示例</h4>
        <pre class="api-code">{
  "notEmpty": true
}</pre>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--post">POST</span>
          <code>/utils/remove-line-breaks</code>
        </div>
        <p class="api-endpoint__desc">替换换行符为空字符串</p>
        <p class="api-endpoint__source">对应方法：replaceLineToEmptyString(value)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>value</td>
              <td>string</td>
              <td>是</td>
              <td>原始字符串</td>
            </tr>
          </tbody>
        </table>
        <h4>响应示例</h4>
        <pre class="api-code">{
  "result": "不含换行的文本"
}</pre>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/utils/row-number</code>
        </div>
        <p class="api-endpoint__desc">获取跨页的行号</p>
        <p class="api-endpoint__source">对应方法：getRowNumber(idEntity, count)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>page</td>
              <td>integer</td>
              <td>是</td>
              <td>当前页码</td>
            </tr>
            <tr>
              <td>size</td>
              <td>integer</td>
              <td>是</td>
              <td>每页数量</td>
            </tr>
            <tr>
              <td>count</td>
              <td>integer</td>
              <td>是</td>
              <td>当前页内序号</td>
            </tr>
          </tbody>
        </table>
        <h4>响应示例</h4>
        <pre class="api-code">{
  "rowNumber": 21
}</pre>
        <p class="api-note">说明：计算公式：(pageIndex - 1) * pageSize + count</p>
      </div>

      <div class="api-endpoint">
        <div class="api-endpoint__head">
          <span class="api-method api-method--get">GET</span>
          <code>/contents/percentage</code>
        </div>
        <p class="api-endpoint__desc">获取元素在列表中的百分比位置</p>
        <p class="api-endpoint__source">对应方法：getPercentage(list, id, completed)</p>
        <h4>请求参数</h4>
        <table class="api-table">
          <thead>
            <tr>
              <th>参数名</th>
              <th>类型</th>
              <th>必填</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>listId</td>
              <td>string</td>
              <td>是</td>
              <td>列表标识</td>
            </tr>
            <tr>
              <td>contentId</td>
              <td>string</td>
              <td>是</td>
              <td>元素ID</td>
            </tr>
            <tr>
              <td>completed</td>
              <td>boolean</td>
              <td>否</td>
              <td>是否已完成，默认false</td>
            </tr>
          </tbody>
        </table>
        <h4>响应示例</h4>
        <pre class="api-code">{
  "percentage": 45
}</pre>
      </div>
    </article>

    <article id="section-pager" class="headless-api-doc__section">
      <h2>分页响应格式</h2>
      <p>所有列表接口支持分页，响应格式统一如下：</p>
      <pre class="api-code">{
  "data": [...],
  "pagination": {
    "page": 1,
    "size": 20,
    "total": 156,
    "totalPages": 8
  }
}</pre>
    </article>

    <article id="section-error" class="headless-api-doc__section">
      <h2>错误响应</h2>
      <pre class="api-code">{
  "error": {
    "code": "NOT_FOUND",
    "message": "内容不存在",
    "timestamp": "2026-07-10T10:30:00Z"
  }
}</pre>
      <table class="api-table">
        <thead>
          <tr>
            <th>HTTP状态码</th>
            <th>错误码</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>400</td>
            <td>BAD_REQUEST</td>
            <td>请求参数错误</td>
          </tr>
          <tr>
            <td>401</td>
            <td>UNAUTHORIZED</td>
            <td>未授权访问</td>
          </tr>
          <tr>
            <td>404</td>
            <td>NOT_FOUND</td>
            <td>资源不存在</td>
          </tr>
          <tr>
            <td>500</td>
            <td>INTERNAL_ERROR</td>
            <td>服务器内部错误</td>
          </tr>
        </tbody>
      </table>
    </article>
  </section>
</template>

<script setup lang="ts">
function scrollTo(id: string) {
  const element = document.getElementById(id);
  if (element) {
    element.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}
</script>

<style scoped>
.headless-api-doc {
  display: grid;
  grid-template-columns: 180px minmax(0, 900px);
  gap: var(--admin-card-gap);
  max-width: 1120px;
  margin: 0 auto;
  padding: 0;
  position: relative;
}

.headless-api-doc__header {
  grid-column: 2;
  margin-bottom: var(--admin-card-gap);
}

.headless-api-doc__header h1 {
  margin: 8px 0;
  font-size: 24px;
}

.headless-api-doc__subtitle {
  margin: 8px 0 0;
  color: var(--admin-text-muted);
  font-size: 14px;
}

.headless-api-doc__toc {
  position: sticky;
  top: 96px;
  grid-column: 1;
  grid-row: 2 / span 20;
  width: 160px;
  max-height: calc(100vh - 100px);
  overflow-y: auto;
}

.toc-nav {
  padding: 12px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-md);
  background: var(--admin-bg-surface);
}

.toc-nav h3 {
  margin: 0 0 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--admin-border-subtle);
  font-size: 14px;
  font-weight: 600;
}

.toc-nav ul {
  margin: 0;
  padding: 0;
  list-style: none;
}

.toc-nav li {
  margin: 0;
  padding: 0;
}

.toc-nav a {
  display: block;
  padding: 6px 8px;
  border-radius: var(--admin-radius-sm);
  color: var(--admin-text-primary);
  font-size: 13px;
  text-decoration: none;
  transition: background-color 0.2s;
}

.toc-nav a:hover {
  background: var(--admin-bg-hover);
}

.headless-api-doc__section {
  grid-column: 2;
  margin-bottom: var(--admin-card-gap);
  padding: 16px;
  border: 1px solid var(--admin-border-subtle);
  border-radius: var(--admin-radius-md);
  background: var(--admin-bg-surface);
}

.headless-api-doc__section h2 {
  margin: 0 0 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--admin-border-subtle);
  font-size: 18px;
}

.api-spec {
  display: grid;
  gap: 8px;
  margin: 0;
}

.api-spec dt {
  color: var(--admin-text-muted);
  font-size: 12px;
}

.api-spec dd {
  margin: 0 0 8px;
}

.api-spec code {
  padding: 2px 6px;
  border-radius: var(--admin-radius-sm);
  background: #f5f5f5;
  font-family: ui-monospace, monospace;
  font-size: 13px;
}

.api-endpoint {
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px dashed var(--admin-border-subtle);
}

.api-endpoint:last-child {
  margin-bottom: 0;
  padding-bottom: 0;
  border-bottom: 0;
}

.api-endpoint__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.api-endpoint__desc {
  margin: 0 0 4px;
  color: var(--admin-text-primary);
  font-size: 14px;
}

.api-endpoint__source {
  margin: 0 0 12px;
  color: var(--admin-text-muted);
  font-size: 12px;
}

.api-endpoint h4 {
  margin: 12px 0 8px;
  color: var(--admin-text-muted);
  font-size: 12px;
  font-weight: 600;
}

.api-note {
  margin: 8px 0 0;
  color: var(--admin-text-muted);
  font-size: 12px;
}

.api-method {
  display: inline-block;
  min-width: 48px;
  padding: 2px 8px;
  border-radius: var(--admin-radius-sm);
  font-size: 11px;
  font-weight: 700;
  text-align: center;
}

.api-method--get {
  background: #d1fae5;
  color: #065f46;
}

.api-method--post {
  background: #dbeafe;
  color: #1e40af;
}

.api-method--put {
  background: #fef3c7;
  color: #92400e;
}

.api-method--delete {
  background: #fee2e2;
  color: #991b1b;
}

.api-endpoint__head code {
  padding: 4px 8px;
  border-radius: var(--admin-radius-sm);
  background: #f5f5f5;
  font-family: ui-monospace, monospace;
  font-size: 13px;
}

.api-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.api-table th,
.api-table td {
  padding: 8px 12px;
  border: 1px solid var(--admin-border-subtle);
  text-align: left;
}

.api-table th {
  background: #fafafa;
  font-weight: 600;
}

.api-table code {
  padding: 1px 4px;
  border-radius: 2px;
  background: #f0f0f0;
  font-family: ui-monospace, monospace;
  font-size: 12px;
}

.api-code {
  margin: 0;
  padding: 12px;
  border-radius: var(--admin-radius-sm);
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: ui-monospace, monospace;
  font-size: 12px;
  overflow-x: auto;
  white-space: pre-wrap;
}

@media (max-width: 900px) {
  .headless-api-doc {
    display: block;
    padding: 0;
  }

  .headless-api-doc__toc {
    position: static;
    width: 100%;
    max-height: none;
    margin-bottom: 20px;
  }
}
</style>
