package com.xbb.content.internal;

import com.xbb.content.api.ContentApi;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "article", schema = "content")
public class Article {

    public enum Status { DRAFT, PUBLISHED, UNPUBLISHED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContentApi.Category category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Article() { }

    public Article(String title, String body, ContentApi.Category category) {
        this.title = title;
        this.body = body;
        this.category = category;
    }

    public void publish() {
        this.status = Status.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    public void unpublish() {
        if (status != Status.PUBLISHED) throw new IllegalStateException("只有已发布的可以下架");
        this.status = Status.UNPUBLISHED;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public ContentApi.Category getCategory() { return category; }
    public Status getStatus() { return status; }
}
