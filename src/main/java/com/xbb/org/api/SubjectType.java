package com.xbb.org.api;

/**
 * 组织的主体类型:公司 or 个人。
 *
 * <p>老板 2026-08-06:**服务站可以是公司,也可以是个人。**
 * 个人没有统一社会信用代码 —— 原来那列是 NOT NULL UNIQUE,个人根本注册不进来。
 *
 * <p>只有服务站可以是个人。招人的企业和工厂必须是公司:
 * 用工主体是个人的话,劳务合同、完税凭证、保证金全都没有落脚点。
 *
 * <p>放在 api 而不是 internal —— 它出现在 {@link OrganizationApproved} 事件和
 * {@link OrgApi.OrgView} 里,留在 internal 会被 ModularityTests 拦下。
 */
public enum SubjectType { COMPANY, INDIVIDUAL }
