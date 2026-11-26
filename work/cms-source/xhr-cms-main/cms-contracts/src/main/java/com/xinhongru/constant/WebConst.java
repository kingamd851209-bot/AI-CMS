package com.xinhongru.constant;

import com.xinhongru.utils.RsaUtils;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/**
 * Created at 2021/8/9.
 *
 * @author 永远的神
 */
@Slf4j
@Component
public class WebConst {
    public static final String OK = "OK";

    /** http默认端口 */
    public static final int HTTP_PORT = 80;

    /** https默认端口 */
    public static final int HTTPS_PORT = 443;

    /** 当前操作系统的路径分隔符 */
    public static final String SEPARATOR = System.getProperty("file.separator");

    /** 电话 固定文本 */
    public static final String TEL_STRING = "电话";

    /** 邮箱 固定文本 */
    public static final String E_MAIL_STRING = "邮箱";

    /** 视频 固定文本 */
    public static final String VIDEO_STRING = "视频";

    /** 初始化静态代码块 */
    static {
        RsaUtils.generateKey();
        log.info("RSA key pair initialized");
    }

    /** 英文逗号 */
    public static final String COMMA_SPLIT = ",";

    /** 请求头中的"Authorization"属性 */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** */
    public static final String HTTP_METHOD_OPTIONS = "OPTIONS";

    /** 脱密数据最多允许显示的星号*个数 */
    public static final int MAX_STAR_COUNT = 3;

    /** 超级管理员账号ID */
    public static final Integer SUPER_ADMIN_ID = 13;

    /** 平台组站点ID：site_id=0 表示跨站点共享的平台组 */
    public static final Long PLATFORM_SITE_ID = 0L;

    /** 会员登录错误redis键 */
    public static final String LOGIN_MEMBER_ERROR_COUNT = "redis_login_member_error_count";

    /** 会员会话key */
    public static final String MEMBER_SESSION_KEY = "member_session_key";

    /** 下划线 */
    public static final String SPLIT_UNDERLINE = "_";

    /** 超级管理员账号名 */
    public static final String SUPER_ADMIN_NAME = "supera";

    /** RSA 密码加密专用 私钥 1024位 */
    public static final String RSA_PRIVATE_KEY = RsaUtils.PRIVATE_KEY;

    /** RSA 密码加密专用 公钥 */
    public static final String RSA_PUBLIC_KEY = RsaUtils.PUBLIC_KEY;

    /** session的key */
    public static String LOGIN_SESSION_KEY = "login_user";

    public static final String CURRENT_SESSION_SITE = "current_session_site";

    /** 登录管理员cookie ID */
    public static final String USER_IN_COOKIE = "S_L_ID";

    /** 分页正则表达式 */
    public static final String REGEX_URL_PAGE = "([&]*)page=(\\d+)";

    /** aes加密加盐 */
    public static String AES_SALT = "0123456789abcdef";

    /** 最大获取文章条数 */
    public static final int MAX_POSTS = 9999;

    /** 最大页码 */
    public static final int MAX_PAGE = 100;

    /** 文章最多可以输入的文字数 */
    public static final int MAX_TEXT_COUNT = 200000;

    /** 文章标题最多可以输入的文字个数 */
    public static final int MAX_TITLE_COUNT = 200;

    /** 点击次数超过多少更新到数据库 */
    public static final int HIT_EXCEED = 10;

    /** 上传文件最大1M */
    public static Integer MAX_FILE_SIZE = 1048576;

    /** 网页前台Title分隔符 */
    public static String TITLE_SPLIT = "_";

    /** 图形验证码前缀 */
    public static final String IMAGE_CODE_PREFIX = "image-code-";

    /** 设置图形验证码过期时间 20分钟 */
    public static final Long IMAGE_CODE_EXPIRE = 20L;

    /** 应用程序运行根目录 */
    public static final String ROOT_PATH = System.getProperty("user.dir");

    /** 正则表达式校验密码必须是包含大小写字母、数字、特殊符号的8位以上,24位以下组合 */
    public static final String PASSWORD_VALIDATE_PATTERN =
            "^(?![A-Za-z0-9]+$)(?![a-z0-9\\W]+$)(?![A-Za-z\\W]+$)(?![A-Z0-9\\W]+$)[a-zA-Z0-9_\\W]{8,24}$";

    /** 默认content type */
    public static final String CONTENT_TYPE_TEXT_HTML_UTF8 = "text/html;charset=UTF-8";

    /** POST请求方法 */
    public static final String REQUEST_METHOD_POST = "POST";

    /** 返回json头 */
    public static final String CONTENT_TYPE_APPLICATION_JSON = "application/json;charset=UTF-8";

    /** utf-8 */
    public static final String CHARSETS_UTF_8 = "UTF-8";

    /** 敏感词设置路径 */
    public static final String SENSITIVE_WORD_SETTING_URL = "/admin/menu_manage/edit";

    /** 管理员管理栏目ID */
    public static final Integer ADMIN_MANAGE_MENU_ID = 6;

    public static final String PROTOCOL_HTTP_PREFIX = "http://";
    public static final String PROTOCOL_HTTPS_PREFIX = "https://";
    public static final String HTML_SUFFIX = ".html";
    public static final String LOCAL_URL = "local_url";
    public static final String BUILDER_HTML_TASK_PREFIX = "build_html_task_";
    public static final String BUILDER_HTML_LIST_PAGE_URL_HASH = "build_html_list_page_url";
    public static final long SECONDS_PER_MINUTE = 60;
    public static final long MINUTES_PER_HOUR = 60;
    public static final long SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR;

    /** 未知 */
    public static final String UNKNOWN = "未知";

    /** 添加权限ID */
    public static final int ADD_AUTHORITY_ID = 1;

    /** 修改权限ID */
    public static final int UPDATE_AUTHORITY_ID = 2;

    /** 删除权限ID */
    public static final int DELETE_AUTHORITY_ID = 3;

    /** 发布/草稿权限ID */
    public static final int APPROVAL_AUTHORITY_ID = 4;

    /** 查看权限ID */
    public static final int VIEW_AUTHORITY_ID = 5;

    /** 排序权限ID */
    public static final int SORT_AUTHORITY_ID = 6;

    /** 复制权限ID */
    public static final int COPY_AUTHORITY_ID = 7;

    /** 首页置顶权限ID */
    public static final int INDEX_TOP_AUTHORITY_ID = 8;

    /** 首页热门权限ID */
    public static final int INDEX_HOT_AUTHORITY_ID = 9;

    /** 首页推荐权限ID */
    public static final int INDEX_ELITE_AUTHORITY_ID = 10;

    /** 频道置顶权限ID */
    public static final int CHANNEL_TOP_AUTHORITY_ID = 11;

    /** 频道热门权限ID */
    public static final int CHANNEL_HOT_AUTHORITY_ID = 12;

    /** 频道推荐权限ID */
    public static final int CHANNEL_ELITE_AUTHORITY_ID = 13;

    /** 栏目置顶权限ID */
    public static final int MENU_TOP_AUTHORITY_ID = 14;

    /** 栏目热门权限ID */
    public static final int MENU_HOT_AUTHORITY_ID = 15;

    /** 栏目推荐权限ID */
    public static final int MENU_ELITE_AUTHORITY_ID = 16;

    /** 导航推荐权限ID */
    public static final int NAV_ELITE_AUTHORITY_ID = 17;

    /** 右侧推荐权限ID */
    public static final int RIGHT_ELITE_AUTHORITY_ID = 18;

    /** 左侧推荐权限ID */
    public static final int LEFT_ELITE_AUTHORITY_ID = 19;

    /** 导出数据权限ID */
    public static final int DATA_OUTPUT_AUTHORITY_ID = 20;

    /** 导入数据权限ID */
    public static final int DATA_INPUT_AUTHORITY_ID = 21;

    /** 状态显示权限ID */
    public static final int STATUS_AUTHORITY_ID = 22;

    /** 审核权限ID */
    public static final int CHECK_AUTHORITY_ID = 23;

    /** 全量发布静态页面 */
    public static final String REDIS_FULL_PUBLISH_KEY = "full_publish_key";

    /** 首页栏目ID */
    public static final Integer HOME_ID = 100;

    /** 常量固定值 1 */
    public static final Integer ONE = 1;

    /** 常量固定值 0 */
    public static final Integer ZERO = 0;

    /** 后台显示 */
    public static final String HAS_MANAGE_CHANNEL = "HasManageChannel";

    /** 前台显示 */
    public static final String HAS_FRONT_CHANNEL = "HasFrontChannel";

    /** 显隐控制 */
    public static final String IS_CAN_SHOW_HIDDEN = "IsCanShowHidden";

    /** 允许添加子栏目 */
    public static final String IS_CAN_ADD_SUB = "IsCanAddSub";

    /** 允许修改 */
    public static final String IS_CAN_EDIT = "IsCanEdit";

    /** 允许删除 */
    public static final String IS_CAN_DELETED = "IsCanDeleted";

    /** 级联删除 */
    public static final String IS_CAN_CASCADE_DELETED = "IsCanCascadeDeleted";

    /** 模块基础栏目 */
    public static final String IS_MODULE_MENU = "IsModuleMenu";

    /** 允许排序 */
    public static final String IS_CAN_SORT = "IsCanSort";

    /** 二级栏目 */
    public static final String IS_SUB_MENU = "IsSubMenu";

    /** 当前位置 */
    public static final String IS_SHOW_CRUMBS = "IsShowCrumbs";

    /** 循环显示 */
    public static final String IS_LOOP = "IsLoop";

    /** 前台显示 */
    public static final String IS_CAN_ADD_ARTICLE = "IsCanAddArticle";

    /** 前台显示 */
    public static final String IS_ADD_ARTICLE = "IsAddArticle";

    /** 整站公共 */
    public static final String WEBSITE_PUBLIC = "WebsitePublic";

    /** 是否表单栏目 */
    public static final String IS_FORM = "IsForm";

    /** 下拉字段开始字符 */
    public static final String SELECT_ELEMENT_STARTS_WITH = "{$";

    /** 下拉字段结束字符 */
    public static final String SELECT_ELEMENT_ENDS_WITH = "$}";

    /** 英文逗号 */
    public static final String COMMA = ",";

    /** 排序号 */
    public static final String SORT_CHINESE = "排序号";

    /** 未修改过任何内容 */
    public static final String NOTHING_HAS_BEEN_MODIFIED = "未修改过任何内容";

    /** 否 */
    public static final String FALSE_CHINESE = "否";

    /** 是 */
    public static final String TRUE_CHINESE = "是";

    /** 修改栏目名称 */
    public static final String UPDATE_CLASS_NAME = "修改栏目名称";

    /** SEO标题 */
    public static final String SEO_TITLE = "SEO标题";

    /** SEO关键词 */
    public static final String SEO_KEYWORD = "SEO关键词";

    /** SEO描述 */
    public static final String SEO_DESCRIPTION = "SEO描述";

    /** 删除栏目 */
    public static final String DELETE_MENU = "删除栏目";

    /** 每页最大显示条数 */
    public static final Integer MAX_PAGE_SIZE = 100;

    /** 简单模板唯一值 */
    public static final String TEMPLATE_1_IDENTIFIES = "7ssk0dMz3ZtSUwjW";

    /** 复杂模板唯一值 */
    public static final String TEMPLATE_2_IDENTIFIES = "AgJWSqgJ9HnWWTXh";
}
