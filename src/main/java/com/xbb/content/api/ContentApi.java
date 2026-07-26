package com.xbb.content.api;

import java.util.List;

/** §4.2:"内容 | 资讯、帮带、轮播 | **完全独立**"——不订阅任何域,也不被任何域依赖。 */
public interface ContentApi {

    enum Category { NEWS, GUIDE }

    record ArticleView(long id, String title, String body, Category category) { }

    record BannerView(long id, String title, String imageUrl, String linkUrl, int weight) { }

    long draftArticle(String title, String body, Category category);

    void publishArticle(long articleId);

    void unpublishArticle(long articleId);

    /** 只返回已发布的——草稿不该泄漏给 C 端。 */
    List<ArticleView> publishedArticles(Category category);

    long draftBanner(String title, String imageUrl, String linkUrl, int weight);

    void publishBanner(long bannerId);

    /** 已发布轮播,按权重降序。 */
    List<BannerView> publishedBanners();
}
