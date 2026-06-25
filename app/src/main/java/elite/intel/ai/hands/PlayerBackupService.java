package elite.intel.ai.hands;

import elite.intel.session.PlayerSession;
import elite.intel.util.AppPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Creates and lists on-demand, user-facing snapshots of every {@code .binds} file in the
 * bindings directory plus {@code StartPreset.*.start}, written to {@code playerbackups}
 * (see {@link AppPaths#getPlayerBackupsDir()}). Deliberately separate from the internal,
 * per-Apply {@link BindingsBackupService}/{@link BindingsApplyService} mechanism, which is an
 * apply-pipeline safety net rather than a user-triggered feature.
 * <p>
 * Backup creation/listing only - restoring a backup is a separate, future piece of work.
 */
public class PlayerBackupService {

    private static final Logger log = LogManager.getLogger(PlayerBackupService.class);
    private static final DateTimeFormatter BACKUP_FOLDER_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static volatile PlayerBackupService instance;

    private final BindingsLoader bindingsLoader;
    private final Clock clock;
    /** Overrides {@link AppPaths#getPlayerBackupsDir()} in tests; {@code null} in production. */
    private final Path baseDirOverride;

    private PlayerBackupService() {
        this(new BindingsLoader(), Clock.systemDefaultZone(), null);
    }

    PlayerBackupService(BindingsLoader bindingsLoader, Clock clock, Path baseDirOverride) {
        this.bindingsLoader = bindingsLoader;
        this.clock = clock;
        this.baseDirOverride = baseDirOverride;
    }

    public static PlayerBackupService getInstance() {
        if (instance == null) {
            synchronized (PlayerBackupService.class) {
                if (instance == null) {
                    instance = new PlayerBackupService();
                }
            }
        }
        return instance;
    }

    /**
     * Sweeps every {@code .binds} file in the bindings directory plus {@code StartPreset.*.start}
     * into a new timestamped folder under {@code playerbackups}, with real filenames intact.
     *
     * @return the folder the backup was written to
     * @throws IOException if the bindings directory has nothing to back up, or the copy fails
     */
    public Path createBackup() throws IOException {
        return createBackup(PlayerSession.getInstance().getBindingsDir());
    }

    /** Test seam: same as {@link #createBackup()} but with an explicit bindings directory. */
    Path createBackup(Path bindingsDir) throws IOException {
        List<Path> filesToBackup = new ArrayList<>(bindingsLoader.listAllBindsFiles(bindingsDir));
        bindingsLoader.findStartPresetFile(bindingsDir).ifPresent(filesToBackup::add);
        if (filesToBackup.isEmpty()) {
            throw new IOException("No .binds or StartPreset files found in " + bindingsDir);
        }

        Path backupFolder = uniqueBackupFolder(resolvePlayerBackupsDir());
        Files.createDirectories(backupFolder);
        for (Path file : filesToBackup) {
            Files.copy(file, backupFolder.resolve(file.getFileName()), StandardCopyOption.COPY_ATTRIBUTES);
        }

        log.info("Created player backup at {} ({} files)", backupFolder, filesToBackup.size());
        return backupFolder;
    }

    /** Lists existing backups, newest first. */
    public List<PlayerBackup> listBackups() throws IOException {
        Path backupRoot = resolvePlayerBackupsDir();
        try (var stream = Files.list(backupRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(this::toPlayerBackup)
                    .sorted(Comparator.comparing(PlayerBackup::timestamp).reversed())
                    .toList();
        }
    }

    private PlayerBackup toPlayerBackup(Path folder) {
        List<String> fileNames;
        try (var stream = Files.list(folder)) {
            fileNames = stream
                    .map(p -> p.getFileName().toString())
                    .sorted(String::compareToIgnoreCase)
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list backup contents in {}: {}", folder, e.getMessage());
            fileNames = List.of();
        }
        return new PlayerBackup(folder, folder.getFileName().toString(), fileNames);
    }

    private Path resolvePlayerBackupsDir() throws IOException {
        if (baseDirOverride != null) {
            Files.createDirectories(baseDirOverride);
            return baseDirOverride;
        }
        return AppPaths.getPlayerBackupsDir();
    }

    /** Appends a numeric suffix on collision (two backups requested within the same second). */
    private Path uniqueBackupFolder(Path backupRoot) {
        String timestamp = ZonedDateTime.now(clock).format(BACKUP_FOLDER_TIMESTAMP);
        Path candidate = backupRoot.resolve(timestamp);
        for (int attempt = 1; Files.exists(candidate); attempt++) {
            candidate = backupRoot.resolve(timestamp + "-" + attempt);
        }
        return candidate;
    }

    /** One backup folder's worth of display info: its timestamp label and the files it contains. */
    public record PlayerBackup(Path folder, String timestamp, List<String> fileNames) {
    }
}
