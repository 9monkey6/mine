## 版本迭代 

### V1.0 【当前版本】& 容器化部署踩坑记录
实现功能：
1. Gateway 网关 SpringBoot 项目本地正常运行、Nacos 注册发现正常
2. 完成 Gateway Docker 容器化打包、镜像构建、容器部署
3. 解决端口占用、容器名冲突、不可执行 jar、Nacos 跨网络注册等问题
项目背景
将 SpringCloud Gateway 网关打包为 Docker 容器化部署，整合 Nacos 注册中心 & 配置中心，实现微服务容器化运行；本地 IDEA 直接启动服务可正常注册 Nacos，Docker 部署后出现各类连通、启动、注册异常，逐步排查解决。
问题 1：docker build 构建镜像命令报错
问题描述
执行 docker build -t gateway:1.0 构建镜像，提示：docker buildx build requires exactly 1 argument
原因
缺少上下文路径，docker build 命令末尾必须指定当前目录上下文
解决方式
使用完整构建命令，指定当前目录：
bash
运行
docker build -t gateway:1.0 .
问题 2：容器启动报错：80 端口被占用
问题描述
启动容器提示：Bind for 0.0.0.0:80 failed: port is already allocated
原因
Gateway 默认内置端口为 80，本机 80 端口被其他程序占用
解决方式
在配置文件手动指定服务端口为 8888，避免端口冲突
Docker 启动使用端口映射，隔离容器端口与宿主机端口
yaml
server:
port: 8888
问题 3：容器名称冲突，无法重复创建容器
问题描述
重复启动容器报错：Conflict. The container name "/gateway" is already in use
原因
旧容器未删除，gateway 容器名称全局唯一，不可重复
解决方式
部署前强制清理旧容器，再重新创建：
bash
运行
docker rm -f gateway
问题 4：Docker 容器启动成功，但服务未运行
问题描述
容器状态正常，但查看日志：no main manifest attribute, in /app.jar
原因
Maven 打包缺少 spring-boot-maven-plugin 插件，打包出的 Jar 为非可执行 Jar，无启动入口
解决方式
在微服务启动模块（Gateway、业务服务） 的 pom.xml 中补充 SpringBoot 打包插件；
公共模块 / 父工程不添加，避免打包异常。
xml
<build>
<plugins>
<plugin>
<groupId>org.springframework.boot</groupId>
<artifactId>spring-boot-maven-plugin</artifactId>
</plugin>
</plugins>
</build>
补充插件后，重新执行 mvn clean package 打包，重建镜像。
问题 5：Docker 服务无法注册到 Nacos（核心难点）
问题描述
本地 IDEA 启动：localhost:8848 连接 Nacos，注册正常；
Docker 容器内使用 localhost:8848：无法连接宿主机 Nacos，服务无法注册；
Windows Docker 对 --net=host 主机网络模式兼容存在缺陷，该方案不稳定。
根本原因
Docker 容器是独立网络环境，容器内 localhost 指向容器自身，并非宿主机；
Windows 平台 Docker 网络驱动限制，无法完美共享宿主机网络。
阶段性方案 & 最终规范方案
临时方案：配置文件硬编码宿主机局域网 IP（192.168.0.113:8848），可临时解决注册问题，但换网络环境需修改代码，不规范、不适合面试 / 生产。
企业级通用方案（最终采用，面试加分）
配置文件使用环境变量占位符，不硬编码任何 IP，代码通用；
本地开发默认使用 localhost，Docker 部署通过启动命令动态注入宿主机 IP；
一套配置适配：本地开发、Docker 容器、服务器生产环境。
yaml
# 最终通用 bootstrap.yml 配置
spring:
application:
name: gateway
cloud:
nacos:
discovery:
server-addr: ${NACOS_HOST:localhost}:8848
config:
server-addr: ${NACOS_HOST:localhost}:8848
file-extension: yaml
server:
port: 8888
bash
运行
# Docker 启动命令：通过 -e 动态传入宿主机IP，配置文件无需修改
docker run -d -p 8888:8888 --name gateway -e NACOS_HOST=192.168.0.113 gateway:1.0
最终总结
容器化部署必须区分容器网络与宿主机网络，禁止直接在容器内使用 localhost 访问本地中间件；
可执行 SpringBoot 项目打包必须依赖专属打包插件，否则容器无法启动；
规避硬编码 IP，使用环境变量 + 配置占位符实现多环境适配，符合企业开发规范；
掌握 Docker 常用运维命令：容器删除、日志排查、端口映射、环境变量注入，快速定位部署异常。


V2.0 迭代更新：Docker-Compose 编排 + 微服务跨容器通信 + 依赖服务优雅启动优化
基于 V1.0 基础上，完成全中间件 + 微服务一体化容器编排，解决集群化部署、容器网络互通、服务启动时序、Nacos 连接拒绝等深度容器化难题，完善生产级部署规范。
新增解决核心问题
问题 6：Docker-Compose 多服务网络隔离，容器间无法互相通信
问题描述
单独部署 Gateway、Nacos、MySQL、Redis 时，各容器网络独立，Gateway 容器内无法通过localhost、容器名访问 Nacos 等中间件，出现java.net.UnknownHostException: nacos未知主机异常。
根本原因
Docker 默认桥接网络隔离，不同容器默认不在同一网桥，容器内 localhost 仅代表自身容器，无法跨容器访问其他服务。
解决方案
统一声明自定义bridge全局网络app-network，所有中间件、微服务统一接入同一网络；
Compose 集群内，直接使用容器名称作为域名跨服务访问，无需硬编码 IP；
依托环境变量占位符配置，容器环境自动读取NACOS_HOST=nacos，实现跨容器域名解析，完美适配多环境。
问题 7：服务启动时序问题，Nacos 未就绪导致 Gateway 注册失败
问题描述
Compose 中depends_on仅控制容器启动顺序，不校验服务业务就绪状态；
Gateway 启动速度远快于 Nacos，频繁出现 Connection refused 连接被拒绝，持续打印 Nacos 接口请求异常。
行业踩坑点
低版本 docker-compose 3 语法不支持 service_healthy 健康依赖条件，无法直接通过配置限制服务就绪顺序，常规延时方案耦合性强、不够通用。
企业级最终解决方案（面试重点）
摒弃固定休眠延时、抛弃高版本语法兼容写法；
改造微服务Dockerfile，集成前置健康检测脚本；
容器启动前置逻辑：通过curl循环轮询检测 Nacos 服务接口，直到 Nacos 完全启动就绪后，再执行 java -jar 启动网关服务；
配合 SpringBoot Nacos 配置：关闭快速失败fail-fast=false，开启后台自动重试，提升服务容错性；
结合容器自动重启策略，实现服务自愈，彻底解决中间件未就绪引发的注册、配置拉取失败问题。
问题 8：Compose 重复部署引发容器名冲突、旧网络残留问题
问题描述
多次修改配置重新部署，残留旧容器、老旧网络、冗余镜像，导致容器命名冲突、网络缓存异常、服务连通异常。
标准化运维方案
规范容器化部署操作命令，上线前完整清理资源：
使用docker-compose down下线服务并销毁自建网络；
冲突容器通过docker rm -f 容器名强制删除；
服务更新采用docker-compose up -d --build重新构建镜像 + 动态部署，保证代码与配置同步生效。
V2.0 技术优化亮点（面试加分项）
完成 Nacos / MySQL / Redis 集群 / Gateway 微服务 全套 Docker-Compose 一键编排部署，本地开发、容器部署环境统一；
标准化容器网络设计：统一网桥 + 容器名域名访问，完全规避 IP 硬编码，符合企业微服务容器化最佳实践；
实现依赖服务优雅启动：自研就绪检测脚本，替代传统固定延迟、高版本健康探针，兼容性更强、适配所有环境；
完善微服务容错配置：Nacos 注册中心关闭快速失败、开启重试机制，解决容器环境下服务注册雪崩问题；
沉淀完整容器化排坑链路：端口冲突、镜像构建、可执行 Jar、网络隔离、跨容器通信、服务时序、资源清理全场景问题闭环。
最终容器化架构总结
基础中间件（Nacos、MySQL、Redis）统一容器化运维，通过 Compose 统一管理生命周期；
微服务与中间件纳入同一网桥网络，基于容器名 DNS 解析实现无感知通信；
采用「环境变量 + 配置占位符」多环境适配方案，一套代码兼容本地开发、容器部署、线上服务器环境；
服务启动采用前置健康轮询 + 业务层重试双层保障，解决微服务集群启动时序经典难题；
沉淀可复用的 Dockerfile 模板、Compose 编排模板、运维操作命令，可直接复用至 SpringCloud 全项目快速容器化落地。