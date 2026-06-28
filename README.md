docker pull mysql:latest

docker run --name some-mysql -e MYSQL_ROOT_PASSWORD=my-secret-pw -p 3306:3306 -d mysql:latest

docker run -p 9090:9000 -p 9001:9001 -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin minio/minio server /data --console-address ":9001"

docker run -d --name=kafka -p 9092:9092 apache/kafka:latest
docker run -d --name kafka -p 9092:9092 bitnamilegacy/kafka
