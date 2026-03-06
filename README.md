# Dassify – Android Telemetry Bridge for IoT Cargo Monitoring

## Descripción del Proyecto

**Dassify** es una plataforma web IoT diseñada para el monitoreo del estado físico de cargas durante su transporte. Este repositorio contiene la **aplicación Android** que actúa como puente de comunicación entre los dispositivos IoT y la infraestructura cloud.

La aplicación recibe datos de telemetría (vibraciones, choques y geolocalización) desde dispositivos basados en **ESP32** mediante **Bluetooth** y los retransmite utilizando **redes LTE/4G** mediante protocolo **UDP** hacia los servicios en **AWS** para su procesamiento y almacenamiento.

---

## Propósito

La aplicación Android cumple un rol fundamental en la arquitectura del sistema:

- **Actuar como gateway** entre los dispositivos IoT y la nube
- **Recibir datos** de sensores desde el ESP32 vía **Bluetooth**
- **Transmitir telemetría** mediante UDP sobre **red LTE/4G** hacia el backend en AWS
- **Facilitar la movilidad** al usar un smartphone como punto de enlace en el vehículo de transporte

---

## Arquitectura del Sistema

El sistema sigue una arquitectura basada en la nube desplegada en **Amazon Web Services (AWS)**.  

Los dispositivos IoT generan datos de telemetría relacionados con la posición y las vibraciones de la carga. Estos datos son transmitidos vía **Bluetooth** a la aplicación Android, que actúa como punto de enlace y los reenvía mediante **UDP sobre red LTE/4G** hacia la infraestructura cloud.

### Diagrama de Arquitectura

![System Architecture](docs/architecture_diagram.png)

---

## Funcionalidades de la App Android

- **Conexión con ESP32** vía **Bluetooth**
- **Lectura de datos** de acelerómetro y GPS
- **Procesamiento y empaquetado** de telemetría
- **Transmisión UDP sobre red LTE/4G** hacia endpoint en AWS
- **Indicadores visuales** de estado de conexión
  
---

## Tecnologías Utilizadas

### Aplicación Android
- **Kotlin / Java**
- **Android SDK**
- **Bluetooth API** para comunicación con ESP32
- **UDP Sockets** para transmisión a la nube
- **GPS Services** para geolocalización
- **Connectivity Manager** para gestión de red LTE/4G

### Infraestructura Cloud (AWS)
- **EC2** - Backend FastAPI
- **RDS** - Base de datos PostgreSQL
- **VPC** - Red privada virtual
- **Load Balancers** - Distribución de tráfico

---

## Mi Contribución al Proyecto

- Desarrollo de la **aplicación Android** para transmisión de telemetría vía **UDP sobre LTE/4G**
- Desarrollo del **backend** con **Python y FastAPI**
- Diseño del **modelo de base de datos PostgreSQL**
- Desarrollo e integración del **frontend web**
- Colaboración en la Integración **Bluetooth** con dispositivos **ESP32**
- Colaboración en la **arquitectura cloud en AWS**

---

## Repositorios Relacionados

- **Plataforma Web (backend + frontend)** — [Dassify-IoT-Platform](https://github.com/MendozaJose2001/Dassify-IoT-Platform)
- **Aplicación Android** — este repositorio
  
---

## Video de Presentación

Video de demostración del proyecto:

https://www.youtube.com/watch?v=TVhq5wv0I18

---

## Autores

Proyecto académico desarrollado por:

- Christopher Cabana  
- Laura Santiago  
- José Daniel Mendoza  
