package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collection;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.openpnp.Translations;
import org.openpnp.gui.components.SubstitutePartPanel;
import org.openpnp.model.Placement;

/**
 * Dialog for replacing the part used by groups of placements on a board.
 */
@SuppressWarnings("serial")
public class SubstitutePartDialog extends JDialog {
    /**
     * Shows the dialog, modal, for the given placements.
     *
     * @param parent component the dialog is centred on.
     * @param boardName shown in the title.
     * @param placements the placements to work on.
     * @param onSubstituted called after placements have been changed.
     */
    public static void show(Component parent, String boardName, Collection<Placement> placements,
            Runnable onSubstituted) {
        SubstitutePartDialog dialog = new SubstitutePartDialog(parent, boardName, placements,
                onSubstituted);
        dialog.setVisible(true);
    }

    private SubstitutePartDialog(Component parent, String boardName,
            Collection<Placement> placements, Runnable onSubstituted) {
        super(SwingUtilities.getWindowAncestor(parent),
                Translations.format("SubstitutePartDialog.Title", boardName), //$NON-NLS-1$
                ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getContentPane().setLayout(new BorderLayout());
        SubstitutePartPanel panel = new SubstitutePartPanel(placements, onSubstituted);
        getContentPane().add(panel, BorderLayout.CENTER);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                panel.dispose();
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        JButton closeButton = new JButton(Translations.getString("SubstitutePartDialog.Close")); //$NON-NLS-1$
        closeButton.addActionListener(e -> dispose());
        buttons.add(closeButton);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(parent);
    }
}
