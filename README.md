# Recurring-Transaction-Management-System

A full-stack web system that enables bank administrators to manage recurring transaction batch processing securely and efficiently.

The system automates the complete payment workflow, including **batch file upload**, **file decryption**, **data validation**, **batch job processing**, **authorization result retrieval**, **automatic report generation**, **merchant management**, and **automatic report delivery**.

Developed using **Angular 19.2.15** for the frontend and **Spring Boot 3.5.3** for the backend.  
Configured with **MySQL**, **Apache Kafka**, and **MinIO** for data persistence, asynchronous messaging, and object storage.

---

# Getting Started

> **Note**: Make sure you have installed all the following tools before starting:
> - **Java 17 or above**
> - **Node.js 18+ and npm**
> - **Maven 3.9+**
> - **Angular CLI 19.2.15** (`npm install -g @angular/cli`)
> - **Docker Desktop**

---

## Step 0: Start Required Services

Pull the MySQL image:

```bash
docker pull mysql:latest
```

Start MySQL:

```bash
docker run --name some-mysql \
-e MYSQL_ROOT_PASSWORD=my-secret-pw \
-p 3306:3306 \
-d mysql:latest
```

Start MinIO:

```bash
docker run \
-p 9090:9000 \
-p 9001:9001 \
-e MINIO_ROOT_USER=minioadmin \
-e MINIO_ROOT_PASSWORD=minioadmin \
minio/minio server /data --console-address ":9001"
```

Start Kafka:

```bash
docker run -d \
--name kafka \
-p 9092:9092 \
bitnamilegacy/kafka
```

---

## Step 1: Configure Database

Create a MySQL database:

```sql
CREATE DATABASE rta_db;
```

Then update `application.properties`:

```properties
spring.datasource.username=root
spring.datasource.password=my-secret-pw
```

> 💡 **Note:**  
> Flyway will automatically execute the SQL migration files (`V1__`, `V2__`, etc.) when the backend starts.  
> You don't need to manually import or execute any `.sql` files.
>
> If Flyway encounters a migration version conflict or checksum issue, run:
>
> ```bash
> mvn flyway:repair
> ```
>
> This will repair Flyway's schema history table and allow migrations to continue normally.

---

## Step 2: Start the Spring Boot Server

Open a terminal and run:

```bash
cd RTAbackend
mvn spring-boot:run
```

The backend will start at:

**http://localhost:8088**

---

## Step 3: Start the Angular Frontend

Open another terminal and run:

```bash
cd RTAfrontend
npm install
ng serve --open

# or

npm start
```

The frontend will start at:

**http://localhost:4200**

---

## System Features

After logging in, bank administrators can:

- Upload encrypted recurring transaction batch files
- Automatically decrypt uploaded files
- Validate transaction data
- Execute batch payment processing jobs
- Monitor batch processing status
- Retrieve authorization results
- Generate reports automatically
- Manage merchant information
- Automatically send reports back to merchants
- View processing history and audit logs

---

# Technology Stack

### Frontend
- Angular 19.2.15
- Angular Material
- TypeScript

### Backend
- Spring Boot 3.5.3
- Spring Data JPA
- Spring Security
- Flyway

### Database
- MySQL

### Message Broker
- Apache Kafka

### Object Storage
- MinIO

---

# Future Enhancements

- Dashboard with real-time batch processing statistics
- Email notifications after batch completion
- AI-powered transaction anomaly detection
- Batch scheduling for recurring transactions
- More comprehensive audit logs and reporting
