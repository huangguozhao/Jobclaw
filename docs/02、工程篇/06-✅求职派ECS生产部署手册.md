# 06-✅求职派ECS生产部署手册

接下来我们将介绍如何将完整体的求职派在服务器上部署起来（以fat-jar方式启动，非docker方式）

## 一、环境准备
### 1.基础资源
硬件： 一台linux系统的ecs服务器

数据库：MySql 8+

jdk: jdk17+

maven:  maven 3.6+

nginx：做网络代理转发

Let's Encrypt: 生成域名证书


### 2.依赖安装
对于一些基础依赖的安装，可以直接参考技术派的部署教程 [✅技术派服务器部署指导手册](https://www.yuque.com/itwanger/az7yww/vgm6ln75fiqstsd4#m4HHw)

#### 2.1 多版本jdk
对于安装指定版本的jdk比较简单，可以直接通过命令安装openjdk

```bash
# ubuntu 更新包索引
sudo apt update

# 安装OpenJDK 17
sudo apt install -y openjdk-17-jdk

# 验证安装
java -version
javac -version

# centos 8
# 安装OpenJDK 17开发包
sudo dnf install -y java-17-openjdk-devel

# 验证安装
java -version
javac -version


# centos 7
# 安装EPEL仓库
sudo yum install -y epel-release

# 安装OpenJDK 17
sudo yum install -y java-17-openjdk-devel

# 验证安装
java -version
```


若系统中已经安装了jdk8，此时为了部署求职派，我们还需要安装jdk17；首先进入 `/usr/lib/jvm`下，判断是否已经有jdk17 （若直接购买的阿里/腾讯的ecs，通常jdk8/17都已经有了）


![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1758780760529-56f3686f-5864-4cd0-80e2-1d728db49ff2.png)

如果没有安装，则可以使用上面的方式进行安装；但是需要注意的是，根据实际需要进行设置默认的jdk


如果当前用户需要默认使用 JDK 17，可以修改用户的 bash 配置：

```bash
echo 'export JAVA_HOME=/usr/lib/jvm/java-17' >> ~/.bashrc
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.bashrc
source ~/.bashrc
```


如果当前用户默认使用JDK8，那么在启动求职派时，需要修改启动脚本

```bash
# 配置jdk17
export JAVA_HOME=/usr/lib/jvm/java-17
export PATH=$JAVA_HOME/bin:$PATH
```


![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1758781001154-0fe12fba-46db-4e5e-b12c-d80d6bac895a.png)


#### 2.2 多版本Maven
通常来讲，我们使用一个maven就可以了，但是当你当前使用的版本 小于 3.4 时，就需要考虑升级了；当然也可以为保留现状，再装一个，比如技术派现在使用的是3.3.9


![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1758781222173-c8b00b5d-3c45-42b9-8ae2-a0ce70b84bf8.png)

因此为了让求职派可以正确打包，我们再装一个

```bash
 wget https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz

 tar -xvf apache-maven-3.9.11-bin.tar.gz
```


同样的，再次调整下脚本中的打包命令 `launch.sh`


![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1758781285120-600620cd-3cf9-49bb-93d2-0295d616ac6c.png)


#### 2.3 MySql安装
求职派的生产环境部署，推荐使用MySql进行数据存储，此时需要我们安装数据库（当然也可以依然使用h2，对于使用h2进行数据存储的可以不安装MySql）

```bash

```

**设置开机启动**

```bash
sudo systemctl start mysqld
sudo systemctl enable mysqld
```

**查询登录密码**

```bash
grep "temporary password" /var/log/mysqld.log

## 输出如下
# A temporary password is generated for root@localhost: xxxx
```

**密码修改:**

方式一：使用`set password`

**格式：**

```plain
mysql> set password for 用户名@localhost = password('新密码');
```

**如：**

```plain
mysql> set password for root@localhost = password('123');
```

方式二：update 方式

```plain
mysql> use mysql;

mysql> update user set password=password('123') where user='root' and host='localhost';

mysql> flush privileges;
```

方式三：alter 方式

```bash
mysql> use mysql;

mysql> alter user 'root'@'localhost' identified by '密码';

mysql> flush privileges;
```


**启动mysql命令**

```bash
# 启动
sudo service mysql start
# 或 sudo service mysqld start

# 关闭
sudo service mysql stop

# 重启
sudo service mysql restart
```


连接 MySQL


![](https://cdn.nlark.com/yuque/0/2024/png/12564477/1731391299118-37b30f59-97f6-4a43-8847-cbbd930939ce.png)

## 二、源码部署
### 1.下载源码
直接使用git clone方式进行下载

```bash
git clone https://github.com/liuyueyi/JobClaw
```


### 2.修改启动脚本
启动脚本为 `launch.sh`，当我们下载项目之后，通常需要为它添加可执行权限

```bash
chmod +x launch.sh
```

然后根据实际的场景，修改 JAVA_HOME 的位置

+ 如果默认就是jdk17，那么可以直接删除下图中的 `JAVA_HOME` 和 `PATH` 这两行
+ 如果默认的不是jdk17，则进入 `/usr/lib/jvm`下，看一下你的java17的文件名，以此来替换下面的 `JAVA_HOME`


![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1758782030269-49d0bf61-487c-4696-a45a-70fe7fa7dcd6.png)

若没有配置maven的系统变量，则还需要更新启动脚本中的mvn前缀


![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1758781285120-600620cd-3cf9-49bb-93d2-0295d616ac6c.png?x-oss-process=image%2Fformat%2Cwebp)


启动脚本中，默认给求职派分配的最大堆为1G；如果机器内存空间足够大，可以自主调整这里的配置

```bash
function run() {
  echo "启动脚本：==========="
  echo "nohup java -server -Xms1g -Xmx1g -Xmn512m -XX:NativeMemoryTracking=detail -XX:-OmitStackTraceInFastThrow -jar ${JAR_NAME} > /dev/null 2>&1 &"
  echo "==========="
  # ms 堆大小  mx 最大堆大小  mn 新生代大小
  nohup $JAVA_HOME/bin/java -server -Dspring.devtools.restart.enabled=false -Xms1g -Xmx1g -Xmn256m -XX:NativeMemoryTracking=detail -XX:-OmitStackTraceInFastThrow -jar ${JAR_NAME} > /dev/null 2>&1 &
  echo $! 1> pid.log
}
```


### 3.修改数据库配置
启动脚本中，默认使用的是prod环境，在prod环境中，使用MySql作为数据库存储；我们现在介绍使用MySql 和 不是用MySql 两种场景的修改方案

#### 3.1 MySql作为数据源
在使用MySql作为数据源时，我们只需要修改`prod/application-dal.yml`配置文件即可

+ 进入配置文件 `vim app/src/main/resources-env/prod/application-dal.yml`
+ 修改 username
+ 修改 password


![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1758782329947-091ab54b-28a6-4f00-9060-9f5442632ac7.png)


:::danger
说明：不用自己创建数据库、不用自己创建表、初始化数据；求职派会自动补全所有的数据库表初始化逻辑

:::


#### 3.2 H2作为数据源
求职派的开发环境，使用的就是h2来存储数据，若我们希望在生产环境也用h2（这样求职派的运行就可以不依赖任何的外部产品）, 可以参照下面的流程


**step1: 修改 pom.xml 配置**


![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1758782652770-c88a5754-fb76-4807-beca-292c72514aa5.png)

**step2: 修改数据源配置文件 prod/application-dal.yml**

+ 进入配置文件 `vim app/src/main/resources-env/prod/application-dal.yml`
+ 替换为下面类似的配置

```yaml
spring:
  datasource:
    # 使用h2数据库，减少外部依赖项
    url: jdbc:h2:file:${user.dir}/datas/${jobclaw.database.name};DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password:
  h2:
    console:
      enabled: false

  # jap配置
  jpa:
    hibernate:
      # 根据实体类更新表结构（默认）
      ddl-auto: update
      naming:
        physical-strategy: org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy
    show-sql: true
    database-platform: org.hibernate.dialect.H2Dialect
```

:::danger
说明：生产环境使用h2时，建议关闭h2-console（即 spring.h2.console.enabled = false）；

其次数据文件的存储路径，可以根据实际的存储路径，来替换上面的项目目录(`${user.dir}/datas/`)

:::


### 4.支付配置修改
求职派集成了微信支付，我们在开发环境，实际上是关闭了微信支付的，你可以在 `dev/application-pay.yml`中关注到 `wx.pay.enable = false`


![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1758782971561-3cd17d2f-7d54-49c9-a67b-4326255aab87.png)

在生产环境部署时，若我们希望正常使用支付能力，则还需要上传证书、修改支付配置

**step1：上传证书**

将支付证书相关文件，放在项目的 ` app/src/main/resource/cert `目录下


![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1758783097384-84243f9c-4c79-43b4-9a6b-6e01a5cdc251.png)


**step2: 修改支付配置**

修改pord环境中的支付配置 **prod/application-pay.yml**

+ 进入配置文件 `vim app/src/main/resources-env/prod/application-pay.yml`
+ 替换为下面类似的配置

```yaml
wx:
  pay:
    # true 表示开启微信支付
    enable: true
    #应用id（小程序id）
    appId: ww17(省略)1a3e3
    #商户号
    merchantId: 1622260872
    #商户API私钥
    privateKey: cert/apiclient_key.pem
    #商户证书序列号
    # openssl x509 -in apiclient_cert.pem -noout -serial
    merchantSerialNumber: 228065B42(省略)0EF17C37A
    #商户APIv3密钥
    apiV3Key: Paico(省略)hui1
    #支付通知地址
    payNotifyUrl: https://oc.paicoding.com/api/wx/payNotify
    #退款通知地址
    refundNotifyUrl: https://oc.paicoding.com/api/wx/refundNotify
```


:::danger
注意：支付证书和配置文件中的参数说明，可以参照技术派文章 [✅技术派集成微信支付（👍强烈推荐）](https://www.yuque.com/itwanger/az7yww/gi9ld7pmcczgtmxd)

:::


### 5.AI密钥配置
在服务器的启动时，ai密钥配置有多种方案，下面介绍几种常见的

#### 5.1 写入配置文件
简单直接方案，直接在 prod/application-ai.yml 配置文件中，维护上真实的api-key

```yaml
spring:
  ai:
    spark:
      # https://console.xfyun.cn/services/cbm
      # api-key 使用你自己申请的进行替换；如果为了安全考虑，可以通过启动参数进行设置
      base-url: https://spark-api-open.xf-yun.com/v1/chat/completions
      api-key: AlEFHiNSdf(省略)lfgLvzJc
      # 这里存储的是基于OpenAI-Starter的大模型交互的配置
      openai-client: true # ture 表示使用OpenAI的client来实现讯飞大模型交互； false 则表示使用自定义的 client 实现讯飞大模型交互
      openai-base-url: https://spark-api-open.xf-yun.com
      chat:
        options:
          model: lite
    openai:
      # openai chatgpt 大模型
      api-key: ${openai-api-key}
    dashscope:
      # 阿里的百炼大模型
      # https://bailian.console.aliyun.com/tab=model#/api-key
      api-key: sk-7c(省略)da20fcd
      # 多模态的图片推理模型
      multi-model: qwen-omni-turbo
      chat:
        options:
          model: qwen-plus-2025-09-11
```

求职派内置的 DeepSeek、硅基流动、阶跃星辰供应商不在 `spring.ai.*` 下，而是配置在 `agent.ai.providers` 下；其中 DeepSeek 复用 OpenAI 兼容协议，`api-style` 为 `openai`，对应的 `base-url` 是 `https://api.deepseek.com`、`completions-path` 是 `/chat/completions`。生产环境推荐直接在后台「LLM供应商」页面维护 API Key、Base URL 和模型清单，具体步骤见 `03-✅求职派大模型-DeepSeek接入.md`。


#### 5.2 启动命令传入
除了直接在配置文件中写上真实的api-key之外，我们还可以通过启动命令传参的方式，将需要的api-key传递给应用，此时我们需要修改 `launch.sh` 脚本

```bash
function run() {
  echo "启动脚本：==========="
  echo "nohup java -server -Xms1g -Xmx1g -Xmn512m -XX:NativeMemoryTracking=detail -XX:-OmitStackTraceInFastThrow -jar ${JAR_NAME} > /dev/null 2>&1 &"
  echo "==========="
  # ms 堆大小  mx 最大堆大小  mn 新生代大小
  nohup $JAVA_HOME/bin/java -server -Dspring.devtools.restart.enabled=false -Xms1g -Xmx1g -Xmn256m -XX:NativeMemoryTracking=detail -XX:-OmitStackTraceInFastThrow -jar ${JAR_NAME} --spark-api-key=AlEFHiNSd(省略)gLvzJc --dashscope-api-key=sk-003a(省略)2702a4 > /dev/null 2>&1 &
  echo $! 1> pid.log
}
```


#### 5.3 设置环境变量
这种方案则是将api-key 设置为系统的环境变量，然后求职派在启动之后从环境变量来读取对应的配置

```bash
echo 'export spark-api-key=AlEFHiNSd(省略)gLvzJc' >> ~/.bashrc
echo 'export dashscope-api-key=sk-003a(省略)2702a4' >> ~/.bashrc
source ~/.bashrc
```


:::warning
说明：求职派配置的apiKey，采用 ${spark-api-key} 占位符方案，Spring Boot 会按照以下优先级顺序来解析这个属性值：

+ 环境变量：可以设置名为 spark-api-key 的环境变量
+ JVM 系统属性：可以通过 -Dspark-api-key=xxx 设置
+ 操作系统环境变量：在 Linux/Mac 中 export SPARK_API_KEY，在 Windows 中 set SPARK_API_KEY=xxx
+ application.properties 或 application.yml 文件中的属性
+ 命令行参数：如 --spark-api-key=xxx

:::


### 6.修改登录配置
求职派目前提供了两种登录方式

+ 基于技术派的静默登录（即技术派登录了，求职派就会自动登录；依赖于一级域名下的cookie共享方案）
+ 基于微信公众号的验证码登录

对于微信公众号验证码登录的场景，我们需要修改 `prod/application-oc.yml`中的 `oc.site.login-qr-img`


![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1758784786983-1565ddb4-8f79-4733-9132-2bbb22cf8cc9.png)

### 7.启动
然后就可以启动项目了，执行命令

```bash
# 给启动命令脚本添加执行权限
chmod +x launch.sh
# 启动
./launch.sh start
```


首次启动，如无异常，则会自动创建库表，输出如下的配置


![](https://cdn.nlark.com/yuque/0/2025/png/35158118/1758784527190-f9141243-58df-4692-81b4-a9f4e973f9eb.png)


### 8.nginx转发配置
项目正常启动之后，最后一步就是绑定域名，对外可用；如求职派的域名为 `oc.paicoding.com`，对应的nginx配置如下

```nginx
upstream  oc_paicoding_host {
    server 127.0.0.1:8087;
}

server {
    listen       80;
    server_name  oc.paicoding.com;
    return       301 https://$host$request_uri;
}

server {
    listen       443 ssl;
    server_name  oc.paicoding.com;

    ssl_certificate     /etc/nginx/ssl/paicoding.com_chain.crt;
    ssl_certificate_key  /etc/nginx/ssl/paicoding.com_key.key;

    ssl_session_cache    shared:SSL:1m;
    ssl_session_timeout  5m;

    ssl_stapling on;
    ssl_stapling_verify on;
    ssl_trusted_certificate /etc/nginx/ssl/paicoding.com_trust.crt;
    resolver 8.8.8.8 8.8.4.4 valid=300s;
    resolver_timeout 5s;

    ssl_ciphers  HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers  on;

    location = /api/wx/subscribe {
      proxy_pass http://oc_paicoding_host;
      proxy_http_version 1.1;

      # 明确 Accept，不透传浏览器的
      proxy_set_header Accept "text/event-stream";
      proxy_set_header Cache-Control "no-cache";

      # 长连接支持
      proxy_set_header Connection keep-alive;
      # 保留必要的头
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

      # 禁用缓冲
      proxy_buffering off;
      proxy_cache off;
      proxy_request_buffering off;

      # 超时设置（SSE 可能几小时不断开）
      proxy_read_timeout 86400s;
      proxy_send_timeout 86400s;
      proxy_connect_timeout 86400s;
    }

    location / {
        proxy_next_upstream error timeout http_502 http_504;
        proxy_set_header X-real-ip  $remote_addr;
        proxy_pass http://127.0.0.1:8087/;
        proxy_redirect default;
        proxy_intercept_errors on;
    }

    # 开启502页面
    error_page  502 503 504 /error.html;
    location = /error.html {
        proxy_set_header  X-Real-IP  $remote_addr;
        root  /home/www/html;
    }

    location ~* ^.+\.(css|js|txt|xml|swf|wav|pptx)$ {
        access_log   off;
        expires      10m;
        proxy_pass         http://oc_paicoding_host;

        proxy_set_header   Host $host;
        proxy_set_header   X-Real-IP $remote_addr;
        proxy_set_header   X-Forwarded-For  $proxy_add_x_forwarded_for;
    }

     location ~* ^.+\.(ico|gif|jpg|jpeg|png)$ {
        access_log   off;
        expires      1d;

        proxy_pass         http://oc_paicoding_host;
        proxy_set_header   Host $host;
        proxy_set_header   X-Real-IP $remote_addr;
        proxy_set_header   X-Forwarded-For  $proxy_add_x_forwarded_for;
    }
}
```


重点说明一下 `/api/wx/subscribe` 配置，这个接口用于公众号登录时，前端像后端发起的SSE请求，后端再接收到用户的验证码之后实现自动登录刷新页面的场景；对于SSE的场景，后端返回的信息，可能因为nginx的缓存策略，导致不会直接将后端发送的信息立即给到前端，从而导致前端无法正常显示`公众号二维码 + 验证码`, 为了解决这个问题，我们在nginx中针对这个接口进行单独配置，禁用缓冲


```nginx
location = /api/wx/subscribe {
      proxy_pass http://127.0.0.1:8087;
      proxy_http_version 1.1;

      # 明确 Accept，不透传浏览器的
      proxy_set_header Accept "text/event-stream";
      proxy_set_header Cache-Control "no-cache";

      # 长连接支持
      proxy_set_header Connection keep-alive;
      # 保留必要的头
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

      # 禁用缓冲
      proxy_buffering off;
      proxy_cache off;
      proxy_request_buffering off;

      # 超时设置（SSE 可能几小时不断开）
      proxy_read_timeout 600s;
      proxy_send_timeout 600s;
      proxy_connect_timeout 600s;
}
```


## 三、小结
本文主要介绍了求职派在ecs服务器上部署的全过程，虽然篇幅看着挺多，实际上需要的动作并不大

+ 安装JDK17
+ 安装Maven
+ 安装MySql数据库
+ 配置Nginx转发
+ 修改生产配置：数据库配置 + AI配置 + 支付配置
+ 直接启动


