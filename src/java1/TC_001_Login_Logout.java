package java1;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;

public class TC_001_Login_Logout {
public static void main(String[] args)throws Exception {
System.setProperty("webdriver.gecko.driver","E:\\software\\Selenium-JAVA\\selenium-java-3.141.59(1)\\Drivers\\geckodriver.exe");
       WebDriver driver=new FirefoxDriver();
driver.get("http://127.0.0.1/orangehrm-2.6/login.php");
Thread.sleep(5000);
driver.findElement(By.name("txtUserName")).sendKeys("admin");
Thread.sleep(5000);
driver.findElement(By.xpath("//input[@name='txtPassword']")).sendKeys("admin");
Thread.sleep(5000);
driver.findElement(By.name("submit")).click();
Thread.sleep(5000);
System.out.println("Login completed");
driver.findElement(By.linkText("Logout")).click();
driver.quit();
}}
