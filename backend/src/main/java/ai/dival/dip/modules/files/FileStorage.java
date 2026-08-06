package ai.dival.dip.modules.files;

import java.io.IOException;
import java.io.InputStream;

/**
 * Where file bytes actually live.
 *
 * <p>An interface so provider-specific code stays out of the domain, per the integration rule in
 * {@code docs/ARCHITECTURE.md}. The filesystem implementation is for local development; an
 * S3-compatible one replaces it without any caller changing.
 *
 * <p>Implementations are given an opaque key and must not interpret it — no directory meaning, no
 * extension handling, no guessing content type from the name.
 */
public interface FileStorage {

    void store(String key, InputStream content, long sizeBytes, String contentType) throws IOException;

    InputStream read(String key) throws IOException;

    void delete(String key) throws IOException;

    boolean exists(String key);
}
