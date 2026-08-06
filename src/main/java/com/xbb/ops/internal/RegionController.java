package com.xbb.ops.internal;

import com.xbb.ops.api.OpsApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 行政区划查询。
 *
 * <p><b>公开,不要登录。</b>小程序发单页第一屏就要选地区,
 * 而没绑过微信的新用户拿不到 token —— 挡住的话他连地区都选不了。
 * 这里回的是省市名称和国标码,不含任何个人信息。
 */
@RestController
@RequestMapping("/api/regions")
class RegionController {

    private final OpsApi opsApi;

    RegionController(OpsApi opsApi) {
        this.opsApi = opsApi;
    }

    /**
     * @param parent 不传则返回省级;传省级码返回它下面的市
     */
    @GetMapping
    ResponseEntity<List<OpsApi.RegionView>> list(@RequestParam(required = false) String parent) {
        return ResponseEntity.ok(opsApi.listRegions(parent));
    }
}
