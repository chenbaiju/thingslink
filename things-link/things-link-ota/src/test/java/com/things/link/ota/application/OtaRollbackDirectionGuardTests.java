package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.things.link.ota.domain.OtaRollbackOperation;
import com.things.link.ota.domain.OtaRollbackRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 提交对账方向真值，不把当前未知回退解释为未执行。 */
class OtaRollbackDirectionGuardTests {
    /** 无操作可继续原对账，缺控制行则必须失败关闭。 */
    @Test void missingOperationAndMissingControlHaveDifferentMeaning() {
        var repository = mock(OtaRollbackRepository.class);
        var guard = new OtaRollbackDirectionGuard(repository);
        UUID job = UUID.randomUUID();
        when(repository.findForJob(job, 1)).thenReturn(Optional.empty());
        assertTrue(guard.commitAllowed(job, 1, job));
        when(repository.deviceConflicted(job)).thenReturn(true);
        assertFalse(guard.commitAllowed(job, 1, job));
        when(repository.deviceConflicted(job)).thenReturn(false);
        var operation = mock(OtaRollbackOperation.class);
        UUID id = UUID.randomUUID();
        when(operation.id()).thenReturn(id);
        when(repository.findForJob(job, 1)).thenReturn(Optional.of(operation));
        when(repository.control(id)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> guard.commitAllowed(job, 1, job));
    }

    /** 只有明确提交先赢且无回退接纳或矛盾才开放旧对账。 */
    @Test void unknownAcceptedAndConflictedDirectionsNeverPermitCommitAdoption() {
        var repository = mock(OtaRollbackRepository.class);
        var guard = new OtaRollbackDirectionGuard(repository);
        var operation = mock(OtaRollbackOperation.class);
        UUID id = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID proof = UUID.randomUUID();
        when(operation.id()).thenReturn(id);
        when(repository.findForJob(job, 1)).thenReturn(Optional.of(operation));
        when(repository.control(id)).thenReturn(Optional.of(control(id, null, null, null)));
        assertFalse(guard.commitAllowed(job, 1, job));
        when(repository.control(id)).thenReturn(Optional.of(control(id, proof, null, null)));
        assertFalse(guard.commitAllowed(job, 1, job));
        when(repository.control(id)).thenReturn(Optional.of(control(id, null, proof, null)));
        assertTrue(guard.commitAllowed(job, 1, job));
        when(repository.deviceConflicted(job)).thenReturn(true);
        assertFalse(guard.commitAllowed(job, 1, job));
        when(repository.deviceConflicted(job)).thenReturn(false);
        when(repository.control(id)).thenReturn(Optional.of(control(id, null, proof, Instant.now())));
        assertFalse(guard.commitAllowed(job, 1, job));
        when(repository.control(id)).thenReturn(Optional.of(control(id, proof, proof, null)));
        assertFalse(guard.commitAllowed(job, 1, job));
    }

    /** 只构造方向事实，不以假的数据库授权替代真实图验证。 */
    private static OtaRollbackRepository.Control control(UUID id, UUID accepted, UUID committed, Instant conflict) {
        return new OtaRollbackRepository.Control(id, accepted, committed, conflict, null, Instant.now());
    }
}
