package group.gnometrading.di;

public abstract class Module {
    @SuppressWarnings("checkstyle:DesignForExtension")
    protected Module[] includes() {
        return new Module[0];
    }
}
