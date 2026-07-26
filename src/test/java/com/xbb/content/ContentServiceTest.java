package com.xbb.content;

import com.xbb.TestcontainersConfig;
import com.xbb.content.api.ContentApi;
import com.xbb.identity.TestCodeAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({TestcontainersConfig.class, TestCodeAccessor.class})
class ContentServiceTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestcontainersConfig.registerProperties(registry);
    }

    @Autowired ContentApi contentApi;

    @Test
    void 草稿不出现在公开列表发布后才可见() {
        long id = contentApi.draftArticle("如何找到靠谱的活", "正文", ContentApi.Category.GUIDE);

        // 草稿泄漏给 C 端是内容系统最常见的事故
        assertThat(contentApi.publishedArticles(ContentApi.Category.GUIDE))
                .extracting(ContentApi.ArticleView::id).doesNotContain(id);

        contentApi.publishArticle(id);

        assertThat(contentApi.publishedArticles(ContentApi.Category.GUIDE))
                .extracting(ContentApi.ArticleView::id).contains(id);
    }

    @Test
    void 下架后不再可见() {
        long id = contentApi.draftArticle("临时公告", "正文", ContentApi.Category.NEWS);
        contentApi.publishArticle(id);

        contentApi.unpublishArticle(id);

        assertThat(contentApi.publishedArticles(ContentApi.Category.NEWS))
                .extracting(ContentApi.ArticleView::id).doesNotContain(id);
    }

    @Test
    void 轮播按权重降序() {
        long low = contentApi.draftBanner("低权重", "http://a", null, 1);
        long high = contentApi.draftBanner("高权重", "http://b", null, 999);
        contentApi.publishBanner(low);
        contentApi.publishBanner(high);

        assertThat(contentApi.publishedBanners().get(0).id()).isEqualTo(high);
    }

    @Test
    void 分类隔离资讯不会混进帮带() {
        long news = contentApi.draftArticle("行业新闻", "正文", ContentApi.Category.NEWS);
        contentApi.publishArticle(news);

        assertThat(contentApi.publishedArticles(ContentApi.Category.GUIDE))
                .extracting(ContentApi.ArticleView::id).doesNotContain(news);
    }
}
