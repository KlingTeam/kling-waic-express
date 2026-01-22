package com.kling.waic.api.config

import com.kling.waic.component.handler.ActivityHandler
import com.kling.waic.component.utils.FileUtils
import com.kling.waic.component.utils.Slf4j.Companion.log
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java
import org.opencv.objdetect.CascadeClassifier
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import redis.clients.jedis.*
import redis.clients.jedis.commands.JedisCommands
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.net.URI


@Configuration
open class ServiceConfig(
    @param:Value("\${REDIS_CLUSTER_MODE_WAIC:false}") private val jedisClusterMode: Boolean,
    @param:Value("\${REDIS_HOST_WAIC:}") private val jedisHost: String,
    @param:Value("\${REDIS_PORT_WAIC:6379}") private val jedisPort: Int,
    @param:Value("\${REDIS_PASSWORD_WAIC:}") private val jedisPassword: String,
    @param:Value("\${S3_PATH_STYLE_ENABLED:false}") private val s3PathStyleEnabled: Boolean,
    @param:Value("\${S3_ENDPOINT:}") private val s3Endpoint: String,
    @param:Value("\${S3_REGION:}") private val s3Region: String,
    @param:Value("\${S3_BUCKET_NAME:kling-waic}") private val bucket: String,
    @param:Value("\${S3_PROFILE_NAME:}") private val s3ProfileName: String,
    @param:Value("\${S3_ACCESS_KEY:}") private val s3AccessKey: String,
    @param:Value("\${S3_SECRET_KEY:}") private val s3SecretKey: String,
    @param:Value("\${REDISSON_PROTOCOL:rediss}") private val redisProtocol: String
) {

    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Bean
    open fun jedis(): JedisCommands {
        val jedisCommands = if (jedisClusterMode) {
            createJedisCluster()
        } else {
            createStandaloneJedisProxy()
        }
        return jedisCommands
    }

    private fun createJedisCluster(): JedisCluster {
        val maxAttempts = 5

        val clientConfig: JedisClientConfig? = DefaultJedisClientConfig.builder()
            .ssl(true)
            .build()

        val jedisCluster = JedisCluster(
            HostAndPort(jedisHost, jedisPort),
            clientConfig,
            maxAttempts,
            ConnectionPoolConfig()
        )
        return jedisCluster
    }

    private fun createStandaloneJedisProxy(): JedisCommands {
        val poolConfig = JedisPoolConfig().apply {
            maxTotal = 50
            minIdle = 5
            maxIdle = 20
            maxWaitMillis = 5000
            testOnBorrow = true
            testOnReturn = true
            testWhileIdle = true
            timeBetweenEvictionRunsMillis = 30000
            minEvictableIdleTimeMillis = 60000
            numTestsPerEvictionRun = 3
        }

        val pool = JedisPool(poolConfig, jedisHost, jedisPort, 3000, jedisPassword)

        return Proxy.newProxyInstance(
            JedisCommands::class.java.classLoader,
            arrayOf(JedisCommands::class.java)
        ) { _, method, args ->
            pool.resource.use { jedis ->
                method.invoke(jedis, *(args ?: emptyArray()))
            }
        } as JedisCommands
    }

    @Bean
    open fun nextCodeLuaScript(): String {
        return FileUtils.readTextFromResources("next-code.lua")
    }

    @Bean
    open fun loadCascadeClassifiersFromResources(): List<CascadeClassifier> {
        Loader.load(opencv_java::class.java)

        // Try to load multiple classifiers in order of priority
        val classifierFiles = listOf(
            "haarcascade_frontalface_default.xml",
            "haarcascade_profileface.xml",
            "haarcascade_frontalface_alt.xml",
            "haarcascade_frontalface_alt2.xml",
        )
        
        val classifiers = mutableListOf<CascadeClassifier>()
        
        for (filename in classifierFiles) {
            try {
                val inputStream = this::class.java.classLoader.getResourceAsStream(filename)
                if (inputStream != null) {
                    val tempFile = File.createTempFile("tmp_${filename.replace(".xml", "")}", ".xml")
                    tempFile.deleteOnExit()
                    FileOutputStream(tempFile).use { output ->
                        inputStream.copyTo(output)
                    }
                    val classifier = CascadeClassifier(tempFile.absolutePath)
                    if (!classifier.empty()) {
                        classifiers.add(classifier)
                        log.info("Successfully loaded cascade classifier: $filename")
                    } else {
                        log.warn("Loaded cascade classifier is empty: $filename")
                    }
                } else {
                    log.debug("Cascade classifier not found in resources: $filename")
                }
            } catch (e: Exception) {
                log.warn("Failed to load cascade classifier: $filename", e)
            }
        }
        
        if (classifiers.isEmpty()) {
            throw IllegalStateException("No cascade classifiers could be loaded from resources")
        }
        
        log.info("Loaded ${classifiers.size} cascade classifiers successfully")
        return classifiers
    }

//    @Bean
//    open fun awsCredentialsProvider(): AwsCredentialsProvider {
//        val credentialsProviderBuilder = DefaultCredentialsProvider.builder()
//        if (s3ProfileName.isNotEmpty()) {
//            credentialsProviderBuilder.profileName(s3ProfileName)
//        }
//        return credentialsProviderBuilder.build()
//    }
//
//    @Bean
//    open fun s3Client(awsCredentialsProvider: AwsCredentialsProvider): S3Client {
//        return S3Client.builder()
//            .region(Region.CN_NORTH_1)
//            .credentialsProvider(awsCredentialsProvider)
//            .build()
//    }

    @Bean
    open fun s3Client(): S3Client {
        val s3Builder = S3Client.builder()

        if (s3Endpoint.isNotEmpty()) s3Builder.endpointOverride(URI.create(s3Endpoint))
        if (s3Region.isNotEmpty()) s3Builder.region(Region.of(s3Region))
        if (s3ProfileName.isNotEmpty()) {
            val credentialsProvider = DefaultCredentialsProvider.builder()
                .profileName(s3ProfileName)
                .build()
            s3Builder.credentialsProvider(credentialsProvider)
        }
        if (s3AccessKey.isNotEmpty() && s3SecretKey.isNotEmpty()) {
            s3Builder.credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(s3AccessKey, s3SecretKey)
                )
            )
        }
        if (s3PathStyleEnabled) {
            s3Builder.serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(s3PathStyleEnabled)
                    .build()
            )
        }
        val s3client = s3Builder.build()

//        deleteBucket(s3client, bucket)
        checkAndCreateBucketPublic(s3client, bucket)
        return s3client
    }

    private fun deleteBucket(s3client: S3Client, bucket: String) {
        try {
            // 1. 列出所有对象
            val objects = s3client.listObjectsV2 { it.bucket(bucket) }.contents()

            // 2. 删除所有对象
            if (!objects.isNullOrEmpty()) {
                val toDelete = objects.map { obj ->
                    ObjectIdentifier.builder().key(obj.key()).build()
                }
                s3client.deleteObjects {
                    it.bucket(bucket)
                        .delete { d -> d.objects(toDelete) }
                }
                log.info("All objects in bucket '$bucket' deleted.")
            }

            // 3. 删除 bucket
            s3client.deleteBucket { it.bucket(bucket) }
            log.info("Bucket '$bucket' deleted successfully.")

        } catch (e: NoSuchBucketException) {
            log.error("Bucket '$bucket' does not exist.", e)
        } catch (e: Exception) {
            log.error("Failed to delete bucket '$bucket': ${e.message}", e)
        }
    }

    private fun checkAndCreateBucketPublic(s3client: S3Client, bucket: String) {
        try {
            s3client.headBucket { it.bucket(bucket) }
            log.info("S3 bucket '$bucket' is accessible")
        } catch (e: Exception) {
            if (e is NoSuchBucketException || e is S3Exception && e.statusCode() == 404) {
                log.warn("Bucket '$bucket' not found, creating it...")
                try {
                    // 创建 bucket
                    s3client.createBucket { it.bucket(bucket) }
                    log.info("S3 bucket '$bucket' created successfully")

                    // 设置 bucket policy 公开访问
                    val publicPolicy = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Principal": "*",
                          "Action": "s3:GetObject",
                          "Resource": "arn:aws:s3:::$bucket/*"
                        }
                      ]
                    }
                """.trimIndent()

                    s3client.putBucketPolicy { it.bucket(bucket).policy(publicPolicy) }
                    log.info("S3 bucket '$bucket' is now public")

                } catch (ce: Exception) {
                    log.error("Failed to create or set public policy for S3 bucket '$bucket'", ce)
                    throw IllegalStateException(
                        "S3 bucket '$bucket' could not be created or made public: ${ce.message}", ce
                    )
                }
            } else {
                log.error("Failed to access S3 bucket '$bucket'", e)
                throw IllegalStateException(
                    "S3 bucket '$bucket' is not accessible: ${e.message}", e
                )
            }
        }
    }

    @Bean
    fun activityHandlerMap(): Map<String, ActivityHandler> {
        val activityHandlers = applicationContext.getBeansOfType(ActivityHandler::class.java)
        val map = mutableMapOf<String, ActivityHandler>()
        activityHandlers.values.forEach { activityHandler ->
            map[activityHandler.activityName()] = activityHandler
        }
        return map
    }

    @Bean(destroyMethod = "shutdown")
    fun redissonClient(): RedissonClient {
        log.info("Configuring Redisson with " +
                "host: '$jedisHost', port: $jedisPort, password: $jedisPassword")

        val config = Config()
        config.useSingleServer()
            .apply {
                address = "${redisProtocol}://${jedisHost}:${jedisPort}"
                connectionMinimumIdleSize = 1
                connectionPoolSize = 10
                if (!jedisClusterMode && jedisPassword.isNotBlank()) {
                    password = jedisPassword
                }
            }
        return Redisson.create(config)
    }
}