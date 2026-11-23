package vn.bigcity;

public final class RequestError extends RuntimeException {
    private final int status;

    public RequestError(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
