package DS4H.maindialog.event;

import java.awt.*;

public class AddRoiEvent implements IMainDialogEvent {
    private Point coords;
    public AddRoiEvent(Point coords) {
        this.coords = coords;
    }

    public Point getClickCoords() {
        return this.coords;
    }
}
