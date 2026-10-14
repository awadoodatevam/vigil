package democontrol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class DemoControlApplicationIT {

  @Autowired private ApplicationContext context;

  @Test
  @DisplayName("BP-R-06 demo-control is a separate application")
  void contextStarts() {
    assertThat(context.getBean(DemoControlApplication.class)).isNotNull();
  }
}
