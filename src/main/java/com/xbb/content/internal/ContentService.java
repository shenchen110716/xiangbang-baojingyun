package com.xbb.content.internal;

import com.xbb.content.api.ContentApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
class ContentService implements ContentApi {

    private final ArticleRepository articles;
    private final BannerRepository banners;

    ContentService(ArticleRepository articles, BannerRepository banners) {
        this.articles = articles;
        this.banners = banners;
    }

    @Override
    @Transactional("contentTransactionManager")
    public long draftArticle(String title, String body, Category category) {
        return articles.save(new Article(title, body, category)).getId();
    }

    @Override
    @Transactional("contentTransactionManager")
    public void publishArticle(long articleId) {
        Article a = articles.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("文章不存在"));
        a.publish();
        articles.save(a);
    }

    @Override
    @Transactional("contentTransactionManager")
    public void unpublishArticle(long articleId) {
        Article a = articles.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("文章不存在"));
        a.unpublish();
        articles.save(a);
    }

    @Override
    @Transactional(transactionManager = "contentTransactionManager", readOnly = true)
    public List<ArticleView> publishedArticles(Category category) {
        return articles.findByCategoryAndStatusOrderByIdDesc(category, Article.Status.PUBLISHED).stream()
                .map(a -> new ArticleView(a.getId(), a.getTitle(), a.getBody(), a.getCategory()))
                .toList();
    }

    @Override
    @Transactional("contentTransactionManager")
    public long draftBanner(String title, String imageUrl, String linkUrl, int weight) {
        return banners.save(new Banner(title, imageUrl, linkUrl, weight)).getId();
    }

    @Override
    @Transactional("contentTransactionManager")
    public void publishBanner(long bannerId) {
        Banner b = banners.findById(bannerId)
                .orElseThrow(() -> new IllegalArgumentException("轮播不存在"));
        b.publish();
        banners.save(b);
    }

    @Override
    @Transactional(transactionManager = "contentTransactionManager", readOnly = true)
    public List<BannerView> publishedBanners() {
        return banners.findByStatusOrderByWeightDesc(Banner.Status.PUBLISHED).stream()
                .map(b -> new BannerView(b.getId(), b.getTitle(), b.getImageUrl(), b.getLinkUrl(), b.getWeight()))
                .toList();
    }
}
