package com.xinhongru.cms.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xinhongru.entity.ContentEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.site.content.ContentLinkService;
import com.xinhongru.web.resonseVo.DetailsVo;
import com.xinhongru.web.service.InfoService;
import com.xinhongru.web.service.MenuService;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.time.LocalDateTime;

class DetailNeighborsTest {
    private static final String SOURCE = "news/details.aspx";
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 23, 12, 0);

    @Test
    void inlineAnchorLabelsRenderOnlyPublishedNeighborsAndRestoreOriginal() {
        String html = """
                <main><div class="article-nav">
                  <div class="article-nav_prev article-nav_disabled"><a href="##">上一篇： 没有了 </a></div>
                  <div class="article-nav_next"><a href="##">下一篇： 原包示例标题 </a></div>
                </div><a href="editor.aspx">联系编辑</a></main>
                """;
        var candidates = DetailNeighbors.inspect(html, SOURCE, null);
        assertThat(candidates).hasSize(1);
        var binding = candidates.getFirst();
        assertThat(binding.candidate().previousHref()).isEqualTo("##");
        assertThat(binding.candidate().previousTitle()).isEqualTo("没有了");
        assertThat(binding.candidate().nextTitle()).isEqualTo("原包示例标题");
        assertThat(binding.candidate().returnHref()).isNull();

        String compiled = DetailNeighbors.compile(html, binding);
        var parsed = Jsoup.parse(html);
        parsed.outputSettings().prettyPrint(false);
        assertThat(DetailNeighbors.withoutInstructions(compiled, binding)).isEqualTo(parsed.outerHtml());
        assertThatThrownBy(() -> DetailNeighbors.withoutInstructions(
                compiled.replace("cmsDetailNeighbors.prevTitle", "cmsDetailNeighbors.nextTitle"), binding))
                .isInstanceOf(IllegalArgumentException.class);

        var source = mock(InfoService.class);
        var menu = new OneshotEntity().setSiteProjectId(58L).setHasFrontChannel(true)
                .setDetailPageUrl("/news/article/{id}.html");
        menu.setId(477);
        var links = new ContentLinkService(mock(MenuService.class));
        var current = article(185, "当前");
        var context = new Context();
        var renderer = new SpringTemplateEngine();
        renderer.setTemplateResolver(new StringTemplateResolver());

        context.setVariable(DetailNeighbors.VARIABLE, DetailNeighbors.project(58L, menu, current, source, links));
        var single = Jsoup.parse(renderer.process(compiled, context));
        assertThat(single.select(".article-nav a")).isEmpty();

        when(source.getNewsNeighbor(58L, 477, TIME, 185, true)).thenReturn(article(186, "新稿 <编辑>"));
        when(source.getNewsNeighbor(58L, 477, TIME, 185, false)).thenReturn(article(184, "旧稿"));
        context.setVariable(DetailNeighbors.VARIABLE, DetailNeighbors.project(58L, menu, current, source, links));
        var multiple = Jsoup.parse(renderer.process(compiled, context));
        assertThat(multiple.select("a[href=editor.aspx]").eachText()).containsExactly("联系编辑");
        var neighbors = multiple.select(".article-nav a");
        assertThat(neighbors.eachText()).containsExactly("上一篇： 新稿 <编辑>", "下一篇： 旧稿");
        assertThat(neighbors.eachAttr("href")).containsExactly("/news/article/186.html", "/news/article/184.html");
        assertThat(neighbors.first().children()).isEmpty();
        assertThat(neighbors.first().parent().classNames()).containsExactly("article-nav_prev");

        when(source.getNewsNeighbor(58L, 477, TIME, 185, true)).thenReturn(null);
        context.setVariable(DetailNeighbors.VARIABLE, DetailNeighbors.project(58L, menu, current, source, links));
        var nextOnly = Jsoup.parse(renderer.process(compiled, context));
        assertThat(nextOnly.select(".article-nav a").eachAttr("href")).containsExactly("/news/article/184.html");
    }

    @Test
    void inlineLabelElementIsPreserved() {
        String html = """
                <main><div class="news_page">
                  <span><a href="javascript:void(0)" class="link disabled"><i>上一篇：</i>无</a></span>
                  <span><a href="details.aspx"><i>下一篇：</i>原包示例标题</a></span>
                </div></main>
                """;
        var binding = DetailNeighbors.inspect(html, SOURCE, null).getFirst();
        String compiled = DetailNeighbors.compile(html, binding);
        var parsed = Jsoup.parse(html);
        parsed.outputSettings().prettyPrint(false);
        assertThat(DetailNeighbors.withoutInstructions(compiled, binding)).isEqualTo(parsed.outerHtml());

        var context = new Context();
        context.setVariable(DetailNeighbors.VARIABLE, new DetailsVo()
                .setPrevTitle("正式标题").setPrevUrl("/news/article/186.html"));
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new StringTemplateResolver());
        var output = Jsoup.parse(engine.process(compiled, context));
        assertThat(output.select(".news_page i").eachText()).containsExactly("上一篇：");
        assertThat(output.select(".news_page a").eachAttr("href")).containsExactly("/news/article/186.html");
        assertThat(output.selectFirst(".news_page a").classNames()).containsExactly("link");
        assertThat(output.select(".news_page a").eachText()).containsExactly("上一篇：正式标题");
    }

    @Test
    void otherLinksAndAmbiguousNavigationStayUnchanged() {
        String other = """
                <header><div><div><a href="##">上一篇：无</a></div><div><a href="##">下一篇：无</a></div></div></header>
                <main>
                  <div><p><a href="#first">相关资讯：新品</a></p><p><a href="#second">下一篇：旧稿</a></p></div>
                  <div><p><a href="1">上一篇推荐：新品</a></p><p><a href="2">下一篇：旧稿</a></p></div>
                  <div><p><a href="3" onclick="return false">上一篇：新品</a></p><p><a href="4">下一篇：旧稿</a></p></div>
                  <div><p><a href="5">上一篇：新品</a></p><p><a href="6">下一篇：旧稿</a></p><a href="7">查看全部</a></div>
                  <a href="index.aspx">返回列表</a>
                </main>
                """;
        assertThat(DetailNeighbors.inspect(other, SOURCE, null)).isEmpty();
        String withPair = other.replace("</main>", """
                <div id="pair"><p>上一篇：<a href="##">无</a></p><p>下一篇：<a href="##">样例</a></p></div></main>
                """);
        var bindings = DetailNeighbors.inspect(withPair, SOURCE, null);
        assertThat(bindings).hasSize(1);
        var compiledHtml = DetailNeighbors.compile(withPair, bindings.getFirst());
        var compiled = Jsoup.parse(compiledHtml);
        assertThat(compiled.select("a[th:href]")).hasSize(2).allMatch(link -> link.closest("#pair") != null);
        assertThat(compiled.select("a[href=index.aspx]").attr("href")).isEqualTo("index.aspx");
        var original = Jsoup.parse(withPair);
        original.outputSettings().prettyPrint(false);
        assertThat(DetailNeighbors.withoutInstructions(compiledHtml, bindings.getFirst()))
                .isEqualTo(original.outerHtml());
    }

    private static ContentEntity article(int id, String title) {
        var result = new ContentEntity().setSiteProjectId(58L).setClassid(477).setCreateTime(TIME)
                .setStatus(1).setTitle(title);
        result.setId(id);
        return result;
    }
}


