package histology.maindialog;

import histology.BufferedImage;
import histology.maindialog.event.ChangeImageEvent;
import histology.maindialog.event.DeleteEvent;
import ij.gui.Roi;
import ij.io.OpenDialog;

import javax.swing.*;
import java.awt.*;
import java.text.MessageFormat;

public class MainDialog extends JDialog {
    private JPanel contentPane;
    private OnDialogEventListener eventListener;

    private JButton btn_delete;
    private JButton btn_prevImage;
    private JButton btn_nextImage;
    private JList<String> list1;

    public MainDialog(OnDialogEventListener listener) {
        super(null, java.awt.Dialog.ModalityType.TOOLKIT_MODAL);
        setContentPane(contentPane);
        setTitle("Histology plugin");
        setModal(true);
        setResizable(false);
        getRootPane().setDefaultButton(btn_prevImage);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setModalityType(ModalityType.MODELESS);

        this.eventListener = listener;

        btn_delete.addActionListener(e -> {
            int index = list1.getSelectedIndex();
            this.eventListener.onEvent(new DeleteEvent(list1.getSelectedIndex()));
            list1.setSelectedIndex(index);
        });
        btn_prevImage.addActionListener(e -> this.eventListener.onEvent(new ChangeImageEvent(ChangeImageEvent.ChangeDirection.PREV)));
        btn_nextImage.addActionListener(e -> this.eventListener.onEvent(new ChangeImageEvent(ChangeImageEvent.ChangeDirection.NEXT)));
        list1.addListSelectionListener(e -> btn_delete.setEnabled(true));

        pack();
        setPreferredSize(new Dimension(this.getWidth(), 300));
        setMinimumSize(new Dimension(this.getWidth(), 300));
        setVisible(true);
    }

    public void setNextImageButtonEnabled(boolean enabled) {
        this.btn_nextImage.setEnabled(enabled);
    }

    public void setPrevImageButtonEnabled(boolean enabled) {
        this.btn_prevImage.setEnabled(enabled);
    }

    public String PromptForFile() {
        OpenDialog od = new OpenDialog("Selezionare un'immagine");
        String dir = od.getDirectory();
        String name = od.getFileName();
        return (dir + name);
    }

    BufferedImage image;
    public void setImage(BufferedImage image) {
        this.image = image;

        DefaultListModel<String> model = new DefaultListModel<>();
        int idx = 0;
        for (Roi roi : image.getManager().getRoisAsArray())
            model.add(idx++, MessageFormat.format("{0} - {1},{2}", idx, (int)roi.getXBase(), (int)roi.getYBase()));
        image.getManager().runCommand("Show All");
        image.getManager().runCommand("show all with labels");
        list1.setModel(model);

        if(list1.getSelectedIndex() == -1)
            btn_delete.setEnabled(false);
    }
}

