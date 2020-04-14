package java1;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;

public class test2 {

	public static void main(String[] args) throws Exception {
        
        //Creating a driver object referencing WebDriver interface
         
        //Setting webdriver.gecko.driver property
        System.setProperty("webdriver.gecko.driver", "E:\\software\\Selenium-JAVA\\selenium-java-3.141.59(1)\\Drivers\\geckodriver.exe");
         
        //Instantiating driver object and launching browser
        WebDriver driver = new FirefoxDriver();
        
        //Using get() method to open a webpage
        driver.get("http://localhost/orangehrm/login.php");
        Thread.sleep(3000);
        driver.findElement(By.name("txtUserName")).sendKeys("admin");
        driver.findElement(By.xpath("/html/body/form/table[1]/tbody/tr/td[2]/table/tbody/tr[1]/td[2]/table/tbody/tr[3]/td[2]/input")).sendKeys("admin");
        
//        to do click action on ant batton
        driver.findElement(By.xpath("/html/body/form/table[1]/tbody/tr/td[2]/table/tbody/tr[1]/td[2]/table/tbody/tr[4]/td[1]/input")).click();
        System.out.println("Click completed");
        Thread.sleep(3000);
        
//        to do logout  by using click(); funnction
        driver.findElement(By.xpath("/html/body/div[3]/ul/li[3]/a")).click();
        System.out.println("Loguot completed");
        
        Thread.sleep(3000);
        //Closing the browser
        
        
        driver.quit();
        System.out.println("Automation completed");
  
    }
}

