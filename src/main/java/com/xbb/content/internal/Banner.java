package com.xbb.content.internal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "banner", schema = "content")
public class Banner {

    public enum Status { DRAFT, PUBLISHED, UNPUBLISHED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Column(name = "link_url", length = 500)
    private String linkUrl;

    @Column(nullable = false)
    private int weight;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Banner() { }

    public Banner(String title, String imageUrl, String linkUrl, int weight) {
        this.title = title;
        this.imageUrl = imageUrl;
        this.linkUrl = linkUrl;
        this.weight = weight;
    }

    public void publish() { this.status = Status.PUBLISHED; }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getImageUrl() { return imageUrl; }
    public String getLinkUrl() { return linkUrl; }
    public int getWeight() { return weight; }
    public Status getStatus() { return status; }
}
