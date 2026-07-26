package com.xbb.content.internal;

import com.xbb.content.api.ContentApi;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ArticleRepository extends JpaRepository<Article, Long> {

    List<Article> findByCategoryAndStatusOrderByIdDesc(ContentApi.Category category, Article.Status status);
}
