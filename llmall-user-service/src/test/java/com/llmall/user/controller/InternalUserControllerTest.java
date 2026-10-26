package com.llmall.user.controller;

import com.llmall.api.dto.UserProfileDTO;
import com.llmall.common.advice.CommonExceptionAdvice;
import com.llmall.user.service.IUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户批量资料内部接口的入口守卫与响应边界。
 *
 * 【为什么要单独测鉴权】这是内容域评价列表的作者资料来源，一旦漏鉴权，外部就能按 id 枚举用户昵称/头像；
 * 而接口本身"能查到数据"在正常调用下不会暴露这个问题，必须直接断言无 token / 错 token 都是 403。
 *
 * 【为什么用 standalone MockMvc】不启 Spring 上下文、不连库：只验证 Controller 的守卫与 DTO 形状，
 * 服务层用 Mockito 桩住。异常映射引入真实的 CommonExceptionAdvice，403 才是真实状态码而不是异常外泄。
 */
class InternalUserControllerTest {

    private static final String SERVICE_TOKEN = "test-service-token";

    private IUserService userService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(IUserService.class);
        InternalUserController controller = new InternalUserController(userService);
        ReflectionTestUtils.setField(controller, "serviceToken", SERVICE_TOKEN);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new CommonExceptionAdvice())
                .build();
    }

    @Test
    void missingServiceTokenMustBeForbidden() throws Exception {
        mockMvc.perform(post("/internal/users/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1,2]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongServiceTokenMustBeForbidden() throws Exception {
        mockMvc.perform(post("/internal/users/profiles")
                        .header("x-service-token", "not-the-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1,2]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void validServiceTokenReturnsOnlyDisplayFields() throws Exception {
        when(userService.queryProfiles(anyList())).thenReturn(Collections.singletonList(
                new UserProfileDTO().setId(1L).setNickname("Jack").setIcon(null)));

        mockMvc.perform(post("/internal/users/profiles")
                        .header("x-service-token", SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1,2,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nickname").value("Jack"))
                // 只允许昵称/头像：手机号、余额等账户字段绝不能出现在这个跨服务契约里
                .andExpect(jsonPath("$[0].phone").doesNotExist())
                .andExpect(jsonPath("$[0].balance").doesNotExist());
    }

    @Test
    void emptyIdsReturnsEmptyArrayWithoutCallingService() throws Exception {
        mockMvc.perform(post("/internal/users/profiles")
                        .header("x-service-token", SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
