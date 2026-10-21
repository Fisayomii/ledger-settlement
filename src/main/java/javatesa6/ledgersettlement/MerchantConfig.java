package javatesa6.ledgersettlement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
public class MerchantConfig {

    @Bean
    public MerchantDirectoryClient merchantDirectoryClient(
            @Value("${" + HttpMerchantDirectoryClient.URL_PROPERTY + ":" + HttpMerchantDirectoryClient.DEFAULT_URL + "}") String url) {
        return new HttpMerchantDirectoryClient(URI.create(url));
    }

    @Bean
    public MerchantLookup merchantLookup(MerchantDirectoryClient merchantDirectoryClient) {
        MerchantLookup lookup = new MerchantLookup(merchantDirectoryClient);
        lookup.table(); // load eagerly, on the startup thread, before the server takes traffic
        return lookup;
    }
}
