package com.javaup.resource;

import com.javaup.dto.ResourceOrderCreateDto;
import com.javaup.resource.enums.InstantAdmissionResultEnum;
import com.javaup.resource.redis.InstantAdmissionLuaExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstantAdmissionLuaExecutorTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private InstantAdmissionLuaExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new InstantAdmissionLuaExecutor(redisTemplate, 24);
    }

    @Test
    void luaResultCodesShouldExposeAdmissionIdempotencySemantics() {
        assertLuaResult(0L, InstantAdmissionResultEnum.ADMITTED_NEW);
        assertLuaResult(-1L, InstantAdmissionResultEnum.SOLD_OUT);
        assertLuaResult(1L, InstantAdmissionResultEnum.ADMITTED_DUPLICATE);
        assertLuaResult(2L, InstantAdmissionResultEnum.DUPLICATE_RELEASED);
        assertLuaResult(-10L, InstantAdmissionResultEnum.IDEMPOTENT_CONFLICT);
    }

    @Test
    void repeatedReleaseResultMustRemainSuccessful() {
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(1L, 0L);

        assertDoesNotThrow(() -> executor.release(20L, "request-1", "digest", 1, false));
        assertDoesNotThrow(() -> executor.release(20L, "request-1", "digest", 1, false));
        verify(redisTemplate, times(2)).execute(
                any(RedisScript.class),
                eq(List.of(
                        "floworder:stock:20",
                        "floworder:instant:admission:request-1",
                        "floworder:instant:unpersisted"
                )),
                any(Object[].class)
        );
    }

    @Test
    void releaseDigestConflictMustFailClosed() {
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(-10L);

        assertThrows(
                IllegalStateException.class,
                () -> executor.release(20L, "request-1", "wrong-digest", 1, false)
        );
    }

    @Test
    void markPersistedShouldAtomicallyExpireCredentialAndRemoveOrphanIndex() {
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(1L);

        executor.markPersisted("request-1");

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(
                        "floworder:instant:admission:request-1",
                        "floworder:instant:unpersisted"
                )),
                any(Object[].class)
        );
    }

    private void assertLuaResult(long code, InstantAdmissionResultEnum expected) {
        reset(redisTemplate);
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(code);
        assertEquals(expected, executor.admit(dto(), "digest"));
    }

    private ResourceOrderCreateDto dto() {
        ResourceOrderCreateDto dto = new ResourceOrderCreateDto();
        dto.setRequestId("request-1");
        dto.setUserId(1001L);
        dto.setResourceId(10L);
        dto.setStockItemId(20L);
        dto.setQuantity(1);
        return dto;
    }
}
