# Vayana Campus Programming Assessment — Problem C: Inventory and Orders

A command-line shop inventory and order management system built with **Java 17**, **plain JDBC**, and **MySQL (InnoDB)**. It guarantees strict ACID compliance and inventory invariants, ensuring that the shop never oversells limited stock even under high concurrent traffic.

---

## 1. Prerequisites & Database Configuration

- **Java**: Java 17+ (JDK)
- **Database**: MySQL 8.0+ running on `localhost:3306` with InnoDB engine
- **Database Name**: `inventory_orders`
- **JDBC Driver**: MySQL Connector/J (located in `lib/mysql-connector-j.jar`)

### Environment Variables
Database credentials and connection settings can be configured via environment variables (passwords are never printed or hardcoded in source files):

| Variable | Default Value | Description |
| :--- | :--- | :--- |
| `DB_URL` | `jdbc:mysql://localhost:3306/inventory_orders?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC` | JDBC connection URL |
| `DB_USER` | `root` | Database username |
| `DB_PASSWORD` | `root` | Database password |

---

## 2. Compilation and How to Run

### Compile the Application and Tests
```powershell
# Create output directory
mkdir bin -ErrorAction SilentlyContinue

# Compile all source files
javac -cp "lib\mysql-connector-j.jar" -d bin src\com\shop\model\*.java src\com\shop\db\*.java src\com\shop\dao\*.java src\com\shop\service\*.java src\com\shop\Main.java src\test\ShopTests.java
```

### Run the Interactive CLI (REPL Mode)
```powershell
java -cp "bin;lib\mysql-connector-j.jar" com.shop.Main
```

### Run Single Command Mode
```powershell
java -cp "bin;lib\mysql-connector-j.jar" com.shop.Main add P1 "Wireless Mouse" 1500 10
java -cp "bin;lib\mysql-connector-j.jar" com.shop.Main order Alice P1:2
java -cp "bin;lib\mysql-connector-j.jar" com.shop.Main low-stock --below 5
java -cp "bin;lib\mysql-connector-j.jar" com.shop.Main cancel 1
java -cp "bin;lib\mysql-connector-j.jar" com.shop.Main simulate
```

### Run the Automated Test Suite
```powershell
java -cp "bin;lib\mysql-connector-j.jar" test.ShopTests
```

---

## 3. Database Schema (InnoDB)

When the application starts, `DatabaseManager` automatically creates the three tables if they do not exist:

```sql
-- 1. PRODUCTS TABLE
CREATE TABLE IF NOT EXISTS products (
    sku         VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    price       BIGINT NOT NULL CHECK (price >= 0),
    stock       INT NOT NULL CHECK (stock >= 0),
    INDEX idx_products_stock (stock)
) ENGINE=InnoDB;

-- 2. ORDERS TABLE
CREATE TABLE IF NOT EXISTS orders (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer    VARCHAR(255) NOT NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status      VARCHAR(32) NOT NULL CHECK (status IN ('PLACED', 'CANCELLED')),
    total       BIGINT NOT NULL CHECK (total >= 0)
) ENGINE=InnoDB;

-- 3. ORDER LINES TABLE
CREATE TABLE IF NOT EXISTS order_lines (
    order_id        BIGINT NOT NULL,
    sku             VARCHAR(64) NOT NULL,
    quantity        INT NOT NULL CHECK (quantity > 0),
    price_at_order  BIGINT NOT NULL CHECK (price_at_order >= 0),
    PRIMARY KEY (order_id, sku),
    CONSTRAINT fk_orderlines_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_orderlines_product FOREIGN KEY (sku) REFERENCES products(sku)
) ENGINE=InnoDB;
```

---

## 4. Commands & Example Runs

### 1. `add <sku> <name> <price> <stock>`
Adds a new product or increases stock for an existing SKU. Prices are specified in whole paise.
```
> add P1 "Wireless Mouse" 1500 10
Product P1 (Wireless Mouse) saved. Stock: 10, Price: 1500 paise

> add P1 "Wireless Mouse" 1500 5
Product P1 (Wireless Mouse) saved. Stock: 15, Price: 1500 paise
```

### 2. `order <customer> <sku>:<qty> [<sku>:<qty>...]`
Places an order with 1 to 5 lines. All or nothing: if any line lacks stock, nothing is deducted and the short SKU is identified.
```
> order Alice P1:2
Order #1 placed. Total: 3000 paise

> order Bob P1:20
Error: SKU P1 is short (requested 20, available 13)
```

### 3. `cancel <order_id>`
Cancels a placed order and restores its stock atomically. Rejects duplicate cancellation.
```
> cancel 1
Order #1 cancelled. Stock restored.

> cancel 1
Error: Order 1 is already cancelled
```

### 4. `low-stock [--below 5]`
Lists products with stock strictly below the threshold (default: 5), ordered lowest first.
```
> low-stock --below 20
Products with stock below 20 (lowest first):
  P1 | Wireless Mouse | stock: 15 | price: 1500 paise
```

### 5. `simulate`
Initializes product `P1` with 10 units. Starts 20 concurrent threads trying to buy 1 unit of `P1` simultaneously.
```
> simulate
Running concurrency simulation (20 threads competing for 10 units of P1)...
Simulation Results:
  Succeeded orders : 10 (Expected: 10)
  Failed (out of stock): 10 (Expected: 10)
  Final stock of P1: 0 (Expected: 0)
  Threading Invariant: PASS (Rule holds)
```

---

## 5. How Concurrency and Thread Safety Remain Correct

1. **InnoDB Pessimistic Row Locking (`SELECT ... FOR UPDATE`)**:
   During order checkout, the service queries requested products using `SELECT ... FOR UPDATE` inside an uncommitted transaction (`conn.setAutoCommit(false)`). This places an exclusive row lock on the product rows, serializing concurrent orders on the same SKU at the database engine level.
2. **Deterministic Lock Ordering (Deadlock Prevention)**:
   When multi-item orders arrive concurrently (e.g., Thread 1 requesting P1, P2 and Thread 2 requesting P2, P1), transactions always sort target SKUs alphabetically prior to acquiring locks. This mathematical ordering eliminates circular-wait deadlocks.
3. **Atomic Verification & Stock Decrement**:
   Stock is validated and decremented using `UPDATE products SET stock = stock - ? WHERE sku = ? AND stock >= ?` within the same transaction that creates the order and order lines. If any SKU is short, `conn.rollback()` restores state completely.
4. **Independent Connections**:
   Each thread in `simulate` obtains its own isolated JDBC `Connection`, ensuring no shared connection state or race conditions in the driver.

---

## 6. What I Would Do With Another Day

1. **Connection Pooling**: Integrate a lightweight connection pool like HikariCP to eliminate connection establishment overhead under high request throughput.
2. **Database Migrations**: Add Flyway or Liquibase scripts to manage versioned database schema upgrades instead of inline DDL.
3. **HTTP REST API**: Wrap `ShopService` in a lightweight web server (e.g. Javalin or standard JDK `HttpServer`) to expose REST endpoints for remote clients.
4. **Soft Deletes & Audit Trails**: Add an audit event table recording who initiated each stock change, order placement, or cancellation with timestamps.
