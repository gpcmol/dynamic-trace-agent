package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Garage demo application.
 *
 * <p>{@code POST /cars} parks a car via {@link Garage#park(Car)} — the method selected in {@code
 * config/agent-config.xml}. {@code POST /cars/background} parks it on a plain new thread, outside
 * any trace (exercises the agent's fallback span). {@code GET /cars} lists parked cars, {@code
 * DELETE /cars} empties the garage (used by the benchmark between phases). The port is taken from
 * {@code PORT} (default 8080).
 */
public final class Main {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Main() {}

  public static void main(final String[] args) throws IOException {
    final int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    final Garage garage =
        new Garage(
            List.of(new Customer("Jan", "Amsterdam")),
            Collections.synchronizedList(new ArrayList<>()));

    final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/cars", new CarsHandler(garage));
    server.createContext("/cars/background", new BackgroundParkHandler(garage));
    server.start();
    System.out.println("Garage running on http://localhost:" + port);
  }

  /** Parks on a new thread without trace context, then answers. */
  private static final class BackgroundParkHandler implements HttpHandler {
    private final Garage garage;

    BackgroundParkHandler(final Garage garage) {
      this.garage = garage;
    }

    @Override
    public void handle(final HttpExchange exchange) throws IOException {
      final Car car = MAPPER.readValue(exchange.getRequestBody(), Car.class);
      final Thread worker = new Thread(() -> garage.park(car), "background-park");
      worker.start();
      try {
        worker.join();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      CarsHandler.send(exchange, 202, "application/json", "{\"status\":\"parked in background\"}");
    }
  }

  private static final class CarsHandler implements HttpHandler {
    private final Garage garage;

    CarsHandler(final Garage garage) {
      this.garage = garage;
    }

    @Override
    public void handle(final HttpExchange exchange) throws IOException {
      switch (exchange.getRequestMethod()) {
        case "GET" -> listCars(exchange);
        case "POST" -> parkCar(exchange);
        case "DELETE" -> emptyGarage(exchange);
        default -> send(exchange, 405, "text/plain", "Method not allowed");
      }
    }

    private void listCars(final HttpExchange exchange) throws IOException {
      final String json;
      synchronized (garage.parkedCars()) {
        json = MAPPER.writeValueAsString(garage.parkedCars());
      }
      send(exchange, 200, "application/json", json);
    }

    private void parkCar(final HttpExchange exchange) throws IOException {
      final Car car = MAPPER.readValue(exchange.getRequestBody(), Car.class);
      garage.park(car);
      send(exchange, 201, "application/json", "{\"status\":\"added\"}");
    }

    private void emptyGarage(final HttpExchange exchange) throws IOException {
      synchronized (garage.parkedCars()) {
        garage.parkedCars().clear();
      }
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
    }

    private static void send(
        final HttpExchange exchange, final int status, final String contentType, final String body)
        throws IOException {
      final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", contentType);
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    }
  }
}
