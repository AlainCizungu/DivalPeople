package ai.dival.dip.modules.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@RequiresDocker
class FileServiceTest extends AbstractIntegrationTest {

    /** SHA-256 of "hello", so the checksum is asserted against a known value, not itself. */
    private static final String HELLO_SHA256 =
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private FileService files;

    private UUID tenantA;
    private UUID tenantB;
    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tenantA = tenants.save(new Tenant("F A", "f-a-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        tenantB = tenants.save(new Tenant("F B", "f-b-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private byte[] hello() {
        return "hello".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("an upload is checksummed and given a random key")
    void uploadsAndChecksums() {
        StoredFile stored = files.upload(
                hello(), "contract.pdf", "application/pdf", "CONTRACT", actor);

        assertThat(stored.getChecksumSha256()).isEqualTo(HELLO_SHA256);
        assertThat(stored.getSizeBytes()).isEqualTo(5);
        assertThat(stored.getOriginalFilename()).isEqualTo("contract.pdf");
        // Random and tenant-namespaced, never derived from the filename.
        assertThat(stored.getStorageKey()).startsWith(tenantA.toString() + "/");
        assertThat(stored.getStorageKey()).doesNotContain("contract");
    }

    @Test
    @DisplayName("the stored bytes come back unchanged")
    void roundTrips() {
        StoredFile stored = files.upload(
                hello(), "contract.pdf", "application/pdf", "CONTRACT", actor);

        FileService.FileContent content = files.download(stored.getId(), actor);

        assertThat(new String(content.content(), StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(content.metadata().getId()).isEqualTo(stored.getId());
    }

    @Test
    @DisplayName("a disallowed content type is refused")
    void refusesDisallowedType() {
        assertThatThrownBy(() -> files.upload(
                hello(), "run.sh", "application/x-sh", "CONTRACT", actor))
                .isInstanceOf(FileService.UnsupportedFileTypeException.class);
    }

    @Test
    @DisplayName("an empty upload is refused")
    void refusesEmpty() {
        assertThatThrownBy(() -> files.upload(
                new byte[0], "empty.pdf", "application/pdf", "CONTRACT", actor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a filename carrying path separators is stripped before it is stored")
    void sanitisesFilename() {
        StoredFile stored = files.upload(
                hello(), "../../etc/passwd", "application/pdf", "CONTRACT", actor);

        assertThat(stored.getOriginalFilename()).doesNotContain("/");
        assertThat(stored.getOriginalFilename()).doesNotContain("\\");
    }

    @Test
    @DisplayName("a category is required")
    void requiresCategory() {
        assertThatThrownBy(() -> files.upload(
                hello(), "contract.pdf", "application/pdf", "  ", actor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("one tenant cannot read another tenant's file")
    void filesAreTenantScoped() {
        StoredFile stored = files.upload(
                hello(), "contract.pdf", "application/pdf", "CONTRACT", actor);

        assertThatThrownBy(() ->
                TenantContext.runAs(tenantB, () -> files.metadata(stored.getId())))
                .isInstanceOf(FileService.FileNotFoundException.class);

        assertThat(files.listByCategory("CONTRACT")).hasSize(1);
        assertThat(TenantContext.runAsResult(tenantB, () -> files.listByCategory("CONTRACT")))
                .isEmpty();
    }
}
