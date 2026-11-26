public class RectangleDemo {
    private final int width;
    private final int height;

    public RectangleDemo(int width, int height) {
        this.width = width;
        this.height = height;
    }

    private int area() {
        return width * height;
    }

    public static void main(String[] args) {
        // Вычисляем площадь прямоугольника.
        RectangleDemo rectangle = new RectangleDemo(6, 4);
        System.out.println("Площадь: " + rectangle.area());
    }
}
