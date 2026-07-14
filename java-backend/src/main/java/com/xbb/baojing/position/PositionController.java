package com.xbb.baojing.position;

import com.xbb.baojing.common.*;
import com.xbb.baojing.enterprise.ActualEmployer;
import com.xbb.baojing.enterprise.ActualEmployerMapper;
import com.xbb.baojing.enterprise.Enterprise;
import com.xbb.baojing.enterprise.EnterpriseMapper;
import com.xbb.baojing.insured.InsuredPersonMapper;
import com.xbb.baojing.plan.InsurancePlanMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class PositionController {
    private final WorkPositionMapper positionMapper;
    private final PositionVideoMapper videoMapper;
    private final ActualEmployerMapper actualEmployerMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final InsuredPersonMapper personMapper;
    private final InsurancePlanMapper planMapper;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final FileTokenService fileTokenService;
    private final String uploadsDir;
    private static final SecureRandom RANDOM = new SecureRandom();

    public PositionController(WorkPositionMapper positionMapper, PositionVideoMapper videoMapper, ActualEmployerMapper actualEmployerMapper,
                               EnterpriseMapper enterpriseMapper, InsuredPersonMapper personMapper, InsurancePlanMapper planMapper,
                               UserMapper userMapper, AuditService auditService, FileTokenService fileTokenService, AppProperties props) {
        this.positionMapper = positionMapper;
        this.videoMapper = videoMapper;
        this.actualEmployerMapper = actualEmployerMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.personMapper = personMapper;
        this.planMapper = planMapper;
        this.userMapper = userMapper;
        this.auditService = auditService;
        this.fileTokenService = fileTokenService;
        this.uploadsDir = props.getUploadsDir();
    }

    public record PositionIn(Integer enterpriseId, String actualEmployer, Integer actualEmployerId, String name,
                              String occupationClass, Integer planId) {}
    public record PositionReviewIn(String occupationClass, String status, Integer planId, String reviewNote) {}
    public record PositionVideoIn(String name, String url) {}
    public record PositionVideoReviewIn(String status, String reviewNote) {}

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private WorkPosition enrich(WorkPosition x) {
        ActualEmployer em = x.getActualEmployerId() != null ? actualEmployerMapper.findById(x.getActualEmployerId()) : null;
        var plan = x.getPlanId() != null ? planMapper.findById(x.getPlanId()) : null;
        User creator = x.getCreatedBy() != null ? userMapper.findById(x.getCreatedBy()) : null;
        List<PositionVideo> videos = videoMapper.findByPosition(x.getId());
        x.setActualEmployerName(em != null ? em.getName() : x.getActualEmployer());
        x.setPlanName(plan != null ? plan.getName() : "");
        x.setCreatorName(creator != null ? creator.getName() : "");
        x.setVideoCount(videos.size());
        x.setLatestVideoStatus(videos.isEmpty() ? "missing" : videos.get(0).getStatus());
        x.setReviewNote(videos.isEmpty() ? "" : videos.get(0).getReviewNote());
        return x;
    }

    private PositionVideo videoDto(PositionVideo v) {
        FileTokenService.Token token = fileTokenService.makeToken("position-video:" + v.getId());
        v.setUrl("/api/positions/" + v.getPositionId() + "/videos/" + v.getId() + "/download?token=" + token.token() + "&expires=" + token.expires());
        return v;
    }

    @GetMapping("/positions")
    public List<WorkPosition> list(User user) {
        Integer scoped = "enterprise".equals(user.getRole()) && user.getEnterpriseId() != null ? user.getEnterpriseId() : null;
        return positionMapper.search(scoped).stream().map(this::enrich).toList();
    }

    @PostMapping("/positions")
    public WorkPosition create(@RequestBody PositionIn data, User user) {
        Integer target = "enterprise".equals(user.getRole()) ? user.getEnterpriseId() : data.enterpriseId();
        if (target == null || enterpriseMapper.findById(target) == null) throw ApiException.badRequest("请先绑定有效投保单位");
        ActualEmployer employer = data.actualEmployerId() != null ? actualEmployerMapper.findById(data.actualEmployerId()) : null;
        if (employer == null || !employer.getEnterpriseId().equals(target)) throw ApiException.badRequest("请选择本企业添加的有效实际工作单位");
        if (!"active".equals(employer.getStatus())) throw ApiException.badRequest("该工作单位已暂停，不能新增岗位");
        WorkPosition item = new WorkPosition();
        item.setEnterpriseId(target);
        item.setActualEmployerId(employer.getId());
        item.setActualEmployer(employer.getName());
        item.setName(data.name());
        item.setOccupationClass("enterprise".equals(user.getRole()) ? "待定" : (data.occupationClass() == null ? "待定" : data.occupationClass()));
        item.setPlanId("enterprise".equals(user.getRole()) ? null : data.planId());
        item.setStatus("pending");
        item.setCreatedBy(user.getId());
        item.setCreatedAt(LocalDateTime.now());
        positionMapper.insert(item);
        auditService.log(user, "create", "position", String.valueOf(item.getId()));
        return enrich(item);
    }

    @PatchMapping("/positions/{id}")
    public WorkPosition update(@PathVariable int id, @RequestBody PositionIn data, User user) {
        WorkPosition item = positionMapper.findById(id);
        if (item == null) throw ApiException.notFound("岗位不存在");
        if ("enterprise".equals(user.getRole()) && !item.getEnterpriseId().equals(user.getEnterpriseId())) throw ApiException.forbidden("无权操作");
        ActualEmployer employer = data.actualEmployerId() != null ? actualEmployerMapper.findById(data.actualEmployerId()) : null;
        if (employer == null || !employer.getEnterpriseId().equals(item.getEnterpriseId())) throw ApiException.badRequest("请选择本企业添加的有效实际工作单位");
        item.setActualEmployerId(employer.getId());
        item.setActualEmployer(employer.getName());
        item.setName(data.name());
        if ("enterprise".equals(user.getRole())) {
            item.setOccupationClass("待定");
            item.setPlanId(null);
            item.setStatus("pending");
        } else {
            item.setOccupationClass(data.occupationClass());
            item.setPlanId(data.planId());
        }
        positionMapper.update(item);
        auditService.log(user, "update", "position", String.valueOf(id));
        return enrich(item);
    }

    @DeleteMapping("/positions/{id}")
    public Map<String, Boolean> delete(@PathVariable int id, User user) {
        WorkPosition item = positionMapper.findById(id);
        if (item == null) throw ApiException.notFound("岗位不存在");
        if ("enterprise".equals(user.getRole()) && !item.getEnterpriseId().equals(user.getEnterpriseId())) throw ApiException.forbidden("无权操作");
        if (positionMapper.countInsured(id) > 0) throw ApiException.conflict("该岗位已关联参保员工，不能删除");
        positionMapper.delete(id);
        auditService.log(user, "delete", "position", String.valueOf(id));
        return Map.of("ok", true);
    }

    @GetMapping("/positions/{id}/videos")
    public List<PositionVideo> videos(@PathVariable int id, User user) {
        WorkPosition pos = positionMapper.findById(id);
        if (pos == null) throw ApiException.notFound("岗位不存在");
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(pos.getEnterpriseId())) throw ApiException.forbidden("无权查看");
        return videoMapper.findByPosition(id).stream().map(this::videoDto).toList();
    }

    private void resetForNewVideo(WorkPosition pos) {
        pos.setStatus("pending");
        pos.setOccupationClass("待定");
        pos.setPlanId(null);
        positionMapper.update(pos);
    }

    @PostMapping("/positions/{id}/videos")
    public PositionVideo addVideo(@PathVariable int id, @RequestBody PositionVideoIn data, User user) {
        WorkPosition pos = positionMapper.findById(id);
        if (pos == null) throw ApiException.notFound("岗位不存在");
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(pos.getEnterpriseId())) throw ApiException.forbidden("无权上传");
        PositionVideo v = new PositionVideo();
        v.setPositionId(id);
        v.setName(data.name());
        v.setUrl(data.url() == null ? "" : data.url());
        v.setCreatedAt(LocalDateTime.now());
        videoMapper.insert(v);
        resetForNewVideo(pos);
        auditService.log(user, "upload", "position_video", String.valueOf(v.getId()));
        return videoDto(v);
    }

    @PostMapping("/positions/{id}/videos/upload")
    public PositionVideo uploadVideo(@PathVariable int id, @RequestParam MultipartFile file, User user) throws IOException {
        WorkPosition pos = positionMapper.findById(id);
        if (pos == null) throw ApiException.notFound("岗位不存在");
        if ("enterprise".equals(user.getRole()) && !user.getEnterpriseId().equals(pos.getEnterpriseId())) throw ApiException.forbidden("无权上传");
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String suffix = original.contains(".") ? original.substring(original.lastIndexOf('.')).toLowerCase() : "";
        if (suffix.isBlank()) {
            String contentType = file.getContentType();
            if ("video/mp4".equalsIgnoreCase(contentType)) suffix = ".mp4";
            else if ("video/quicktime".equalsIgnoreCase(contentType)) suffix = ".mov";
        }
        if (!Set.of(".mp4", ".mov", ".m4v").contains(suffix)) throw ApiException.badRequest("仅支持 MP4、MOV 或 M4V 视频");
        if (file.getSize() > 100L * 1024 * 1024) throw ApiException.badRequest("岗位视频不能超过 100MB");
        Path folder = Paths.get(uploadsDir, "positions", String.valueOf(id));
        Files.createDirectories(folder);
        String stored = randomHex(8) + suffix;
        Files.write(folder.resolve(stored), file.getBytes());
        PositionVideo v = new PositionVideo();
        v.setPositionId(id);
        v.setName(original.isBlank() ? stored : original);
        v.setUrl("/uploads/positions/" + id + "/" + stored);
        v.setCreatedAt(LocalDateTime.now());
        videoMapper.insert(v);
        resetForNewVideo(pos);
        auditService.log(user, "upload", "position_video", String.valueOf(v.getId()));
        return videoDto(v);
    }

    @GetMapping("/positions/{positionId}/videos/{videoId}/download")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> download(
            @PathVariable int positionId, @PathVariable int videoId, @RequestParam String token, @RequestParam long expires) throws IOException {
        if (!fileTokenService.verify("position-video:" + videoId, expires, token)) throw ApiException.forbidden("下载链接无效或已过期");
        PositionVideo video = videoMapper.findById(videoId);
        if (video == null || !video.getPositionId().equals(positionId)) throw ApiException.notFound("岗位视频不存在");
        if (video.getUrl().startsWith("http://") || video.getUrl().startsWith("https://")) {
            return org.springframework.http.ResponseEntity.status(302).header("Location", video.getUrl()).build();
        }
        Path path = Paths.get(".", video.getUrl());
        if (!Files.isRegularFile(path)) throw ApiException.notFound("文件不存在");
        return org.springframework.http.ResponseEntity.ok().body(new org.springframework.core.io.UrlResource(path.toUri()));
    }

    @PatchMapping("/position-videos/{id}/review")
    public PositionVideo reviewVideo(@PathVariable int id, @RequestBody PositionVideoReviewIn data, User user) {
        Rbac.requireRole(user, "仅平台端可审核岗位视频", "admin");
        PositionVideo item = videoMapper.findById(id);
        if (item == null) throw ApiException.notFound("岗位视频不存在");
        item.setStatus(data.status());
        item.setReviewNote(data.reviewNote() == null ? "" : data.reviewNote());
        videoMapper.update(item);
        auditService.log(user, "review", "position_video", String.valueOf(id), data.status());
        return videoDto(item);
    }

    @DeleteMapping("/position-videos/{id}")
    public Map<String, Boolean> deleteVideo(@PathVariable int id, User user) throws IOException {
        Rbac.requireRole(user, "仅平台端可删除岗位视频", "admin");
        PositionVideo item = videoMapper.findById(id);
        if (item == null) throw ApiException.notFound("岗位视频不存在");
        if (!item.getUrl().startsWith("http://") && !item.getUrl().startsWith("https://")) {
            Path path = Paths.get(".", item.getUrl());
            Files.deleteIfExists(path);
        }
        videoMapper.delete(id);
        auditService.log(user, "delete", "position_video", String.valueOf(id));
        return Map.of("ok", true);
    }

    @PatchMapping("/positions/{id}/review")
    public WorkPosition review(@PathVariable int id, @RequestBody PositionReviewIn data, User user) {
        Rbac.requireRole(user, "仅平台端可确定岗位职业类别", "admin");
        WorkPosition item = positionMapper.findById(id);
        if (item == null) throw ApiException.notFound("岗位不存在");
        List<PositionVideo> videos = videoMapper.findByPosition(id);
        if ("approved".equals(data.status()) && videos.isEmpty()) throw ApiException.badRequest("岗位视频上传后才能完成定类");
        if ("approved".equals(data.status()) && (data.occupationClass() == null || data.occupationClass().isBlank())) throw ApiException.badRequest("请选择岗位职业类别");
        if (Set.of("supplement", "rejected").contains(data.status()) && (data.reviewNote() == null || data.reviewNote().isBlank())) throw ApiException.badRequest("补件或驳回时必须填写审核意见");
        if (data.planId() != null && planMapper.findById(data.planId()) == null) throw ApiException.badRequest("投保方案不存在");
        if (data.occupationClass() != null && !data.occupationClass().isBlank()) item.setOccupationClass(data.occupationClass());
        item.setPlanId(data.planId());
        item.setStatus(data.status());
        positionMapper.update(item);
        if (!videos.isEmpty()) {
            PositionVideo latest = videos.get(0);
            latest.setStatus(data.status());
            latest.setReviewNote(data.reviewNote() == null ? "" : data.reviewNote());
            videoMapper.update(latest);
        }
        auditService.log(user, "review", "position", String.valueOf(id), data.status() + ":" + item.getOccupationClass());
        return enrich(item);
    }
}
