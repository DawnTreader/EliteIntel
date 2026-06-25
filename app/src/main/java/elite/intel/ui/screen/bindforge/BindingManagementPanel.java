package elite.intel.ui.screen.bindforge;

import elite.intel.ai.hands.PlayerBackupService;
import elite.intel.ui.theme.AppTheme;
import elite.intel.ui.widget.HudFooter;
import elite.intel.ui.widget.HudPanel;
import elite.intel.ui.widget.HudSection;
import elite.intel.ui.widget.HudTable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.util.List;

import static elite.intel.ui.i18n.MultiLingualTextProvider.getText;
import static elite.intel.ui.theme.AppTheme.*;
import static elite.intel.ui.theme.HudPalette.*;

/**
 * The "Binding Management" sub-tab of BIND FORGE: a manual "Backup Now" action plus a flat list
 * of existing {@code playerbackups} snapshots. Backup creation/listing only - restore is a
 * separate, future piece of work, so this panel has no restore actions yet.
 */
public class BindingManagementPanel extends JPanel {

    private static final Logger log = LogManager.getLogger(BindingManagementPanel.class);

    private final PlayerBackupService backupService = PlayerBackupService.getInstance();

    private DefaultTableModel tableModel;
    private JButton backupNowButton;
    private boolean backupInProgress;

    public BindingManagementPanel() {
        buildUi();
    }

    private void buildUi() {
        setLayout(new BorderLayout());
        setBorder(hudSubtabContentBorder());
        setBackground(HUD_COLOR_ROLE_APPLICATION_BACKGROUND);

        tableModel = new ReadOnlyTableModel(columnNames(), 0);
        JTable table = new JTable(tableModel);
        HudTable.style(table);

        HudSection section = new HudSection(
                getText("bindForge.bindingManagement.section.backups"),
                new BorderLayout(),
                HudPanel.Variant.FLAT,
                6);
        section.body().add(HudTable.dataPlaneScrollPane(table), BorderLayout.CENTER);

        add(section, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
    }

    private JPanel buildFooter() {
        backupNowButton = makeButton(getText("bindForge.bindingManagement.button.backupNow"));
        backupNowButton.addActionListener(e -> performBackup());
        return HudFooter.build(false, null, null, List.of(backupNowButton));
    }

    public void initData() {
        refreshBackups();
    }

    /**
     * Runs the backup sweep off the EDT, same as {@code BindingProfilePanel}'s
     * {@code applyPlanInBackground()} - a multi-file copy would otherwise freeze the UI.
     */
    private void performBackup() {
        if (backupInProgress) {
            return;
        }
        setBackupBusy(true);
        new Thread(() -> {
            IOException failure = null;
            try {
                backupService.createBackup();
            } catch (IOException e) {
                failure = e;
            }
            IOException result = failure;
            SwingUtilities.invokeLater(() -> {
                setBackupBusy(false);
                if (result != null) {
                    showBackupError(result);
                } else {
                    refreshBackups();
                }
            });
        }, "PlayerBackup-Thread").start();
    }

    private void setBackupBusy(boolean busy) {
        backupInProgress = busy;
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        backupNowButton.setEnabled(!busy);
    }

    private void showBackupError(IOException e) {
        JOptionPane.showMessageDialog(
                this,
                getText("bindForge.bindingManagement.backup.error", e.getMessage()),
                getText("bindForge.bindingManagement.backup.dialogTitle"),
                JOptionPane.ERROR_MESSAGE);
    }

    private void refreshBackups() {
        tableModel.setRowCount(0);
        try {
            for (PlayerBackupService.PlayerBackup backup : backupService.listBackups()) {
                tableModel.addRow(new Object[]{
                        backup.timestamp(),
                        String.join(", ", backup.fileNames())
                });
            }
        } catch (IOException e) {
            // Leave the table empty; the user can still retry via Backup Now.
            log.warn("Could not list player backups: {}", e.getMessage());
        }
    }

    private String[] columnNames() {
        return new String[]{
                getText("bindForge.bindingManagement.column.created"),
                getText("bindForge.bindingManagement.column.files")
        };
    }

    private static final class ReadOnlyTableModel extends DefaultTableModel {
        private ReadOnlyTableModel(Object[] columnNames, int rowCount) {
            super(columnNames, rowCount);
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    }
}
