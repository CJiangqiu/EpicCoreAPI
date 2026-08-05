package net.eca.client.render.shader_generator;

interface PreviewAnimation extends AutoCloseable {

    void update();

    @Override
    void close();
}
