/*
 * Copyright 2015 Brady Wood, Branko Juric
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gwen.web

import java.net.URL
import java.util.concurrent.TimeUnit

import org.openqa.selenium.{MutableCapabilities, WebDriver}
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxOptions, FirefoxProfile}
import org.openqa.selenium.ie.{InternetExplorerDriver, InternetExplorerOptions}
import org.openqa.selenium.remote.CapabilityType
import org.openqa.selenium.remote.DesiredCapabilities
import org.openqa.selenium.remote.HttpCommandExecutor
import org.openqa.selenium.remote.RemoteWebDriver
import org.openqa.selenium.safari.{SafariDriver, SafariOptions}
import com.typesafe.scalalogging.LazyLogging
import gwen.Predefs.Kestrel
import gwen.errors._
import gwen.web.errors._

import collection.JavaConverters._
import scala.collection.mutable

/**
  * Provides and manages access to the web drivers.
  */
class DriverManager extends LazyLogging {
    
  /** Map of web driver instances (keyed by name). */
  private[web] val drivers: mutable.Map[String, WebDriver] = mutable.Map()

  /** The current web browser session. */
  private var session = "primary"

  /** Current stack of windows. */
  private val windows = mutable.Stack[String]()
    
  /** Provides private access to the web driver */
  private def webDriver: WebDriver = drivers.getOrElse(session, {
      loadWebDriver tap { driver =>
        drivers += (session -> driver)
        windows push driver.getWindowHandle
      }
    })

  /** Resets the driver manager. */
  def reset() {
    session = "primary"
    windows.clear()
  }

  /** Quits all browsers and closes the web drivers (if any have loaded). */
  def quit() {
    drivers.keys.foreach(quit)
  }

  /** Quits a named browser and associated web driver instance. */
  def quit(name: String) {
    drivers.get(name) foreach { driver =>
      logger.info(s"Closing browser session${ if(name == "primary") "" else s": $name"}")
      driver.quit()
      drivers.remove(name)
    }
    session = "primary"
  }

   /**
    * Invokes a function that performs an operation on the current web driver.
    *
    * @param f the function to perform
    */
  def withWebDriver[T](f: WebDriver => T): T = f(webDriver)
  
  /** Loads the selenium webdriver. */
  private[web] def loadWebDriver: WebDriver = withGlobalSettings {
    val driverName = WebSettings.`gwen.web.browser`.toLowerCase
    logger.info(s"Starting $driverName browser session${ if(session == "primary") "" else s": $session"}")
    WebSettings.`gwen.web.remote.url` match {
      case Some(addr) => remoteDriver(driverName, addr)
      case None => localDriver(driverName)
    }
  }
  
  private def remoteDriver(driverName: String, addr: String): WebDriver = {
    val capabilities: DesiredCapabilities = driverName match {
      case "firefox" => DesiredCapabilities.firefox tap { capabilities =>
        capabilities.setCapability(FirefoxDriver.PROFILE, firefoxOptions().getProfile)
      }
      case "chrome" => DesiredCapabilities.chrome tap { capabilities =>
        capabilities.setCapability(ChromeOptions.CAPABILITY, chromeOptions())
        capabilities.setCapability(CapabilityType.ACCEPT_SSL_CERTS, WebSettings.`gwen.web.accept.untrusted.certs`)
      }
      case "ie" => DesiredCapabilities.internetExplorer() tap { capabilities =>
        capabilities.setCapability(CapabilityType.ACCEPT_SSL_CERTS, WebSettings.`gwen.web.accept.untrusted.certs`)
      }
    }
    capabilities.setJavascriptEnabled(true)
    setDesiredCapabilities(capabilities)
    remote(addr, capabilities)
  }
  
  /**
    * Gets the local web driver for the given name.
    * 
    *  @param driverName the name of the driver to get
    *  @throws gwen.web.errors.UnsupportedWebDriverException if the given
    *          web driver name is unsupported 
    */
  private def localDriver(driverName: String): WebDriver = driverName match {
    case "firefox" => firefox()
    case "ie" => ie()
    case "chrome" => chrome()
    case "safari" => safari()
    case _ => unsupportedWebDriverError(driverName)
  }
  
  private def firefoxOptions() : FirefoxOptions = {
    val firefoxProfile = new FirefoxProfile() tap { profile =>
      WebSettings.`gwen.web.useragent` foreach {
        profile.setPreference("general.useragent.override", _)
      }
      if (WebSettings.`gwen.web.authorize.plugins`) {
        profile.setPreference("security.enable_java", true)
        profile.setPreference("plugin.state.java", 2)
      }
      WebSettings.`gwen.web.accept.untrusted.certs` tap { _ =>
        profile.setAcceptUntrustedCertificates(true)
        profile.setAssumeUntrustedCertificateIssuer(false)
      }
      if (WebSettings.`gwen.web.suppress.images`) {
        profile.setPreference("permissions.default.image", 2)
      }
    }
    new FirefoxOptions()
      .setProfile(firefoxProfile)
      .merge(DesiredCapabilities.firefox()) tap { options =>
      if (WebSettings.`gwen.web.browser.headless`) {
        logger.info(s"Firefox will run headless")
        options.addArguments("-headless")
      }
      setDesiredCapabilities(options.asInstanceOf[MutableCapabilities])
    }
  }
  
  private def chromeOptions() : ChromeOptions = new ChromeOptions() tap { options =>
    WebSettings.`gwen.web.useragent` foreach { 
      agent => options.addArguments(s"--user-agent=$agent") 
    }
    if (WebSettings.`gwen.web.authorize.plugins`) {
      options.addArguments("--always-authorize-plugins") 
    }
    options.addArguments("--test-type")
    if (WebSettings.`gwen.web.accept.untrusted.certs`) {
      options.addArguments("--ignore-certificate-errors")
    }
    WebSettings.`gwen.web.chrome.args` foreach { arg =>
      logger.info(s"Setting chrome driver argument: $arg")
      options.addArguments(arg)
    }
    if (WebSettings.`gwen.web.browser.headless`) {
      logger.info(s"Chrome will run headless")
      options.addArguments("headless")
    }
    val prefs = new java.util.HashMap[String, Object]()
    WebSettings.`gwen.web.chrome.prefs` foreach { case (name, value) =>
      logger.info(s"Setting chrome browser preference: $name=$value")
      try {
        prefs.put(name, Integer.valueOf(value.trim))
      } catch {
        case _: Throwable =>
          if (value.matches("(true|false)")) prefs.put(name, java.lang.Boolean.valueOf(value.trim))
          else prefs.put(name, value)
      }
    }
    if (!prefs.isEmpty) {
      options.setExperimentalOption("prefs", prefs)
    }
    WebSettings.`gwen.web.chrome.extensions` tap { extensions =>
      if (extensions.nonEmpty) {
        logger.info(s"Loading chrome extension${if (extensions.size > 1) "s" else ""}: ${extensions.mkString(",")}")
        options.addExtensions(extensions:_*)
      }
    }
    setDesiredCapabilities(options.asInstanceOf[MutableCapabilities])
  }

  private def ieOptions(): InternetExplorerOptions = new InternetExplorerOptions() tap { options =>
    val capabilities = options.asInstanceOf[MutableCapabilities]
    capabilities.setCapability(CapabilityType.ACCEPT_SSL_CERTS, true)
    setDesiredCapabilities(capabilities)
  }

  private def safariOptions(): SafariOptions = new SafariOptions() tap { options =>
    setDesiredCapabilities(options.asInstanceOf[MutableCapabilities])
  }

  private def setDesiredCapabilities(capabilities: MutableCapabilities) {
    WebSettings.`gwen.web.capabilities` foreach { case (name, value) =>
      logger.info(s"Setting web capability: $name=$value")
      try {
        capabilities.setCapability(name, Integer.valueOf(value.trim))
      } catch {
        case _: Throwable =>
          if (value.matches("(true|false)")) capabilities.setCapability(name, java.lang.Boolean.valueOf(value.trim))
          else capabilities.setCapability(name, value)
      }
    }
  }
  
  private[web] def chrome(): WebDriver = new ChromeDriver(chromeOptions())
  
  private[web] def firefox(): WebDriver = new FirefoxDriver(firefoxOptions())
  
  private[web] def ie(): WebDriver = new InternetExplorerDriver(ieOptions())
  
  private[web] def safari(): WebDriver = new SafariDriver(safariOptions())
  
  private[web] def remote(hubUrl: String, capabilities: DesiredCapabilities): WebDriver =
    new RemoteWebDriver(new HttpCommandExecutor(new URL(hubUrl)), capabilities)
  
  private def withGlobalSettings(driver: WebDriver): WebDriver = {
    driver.manage().timeouts().implicitlyWait(WebSettings.`gwen.web.wait.seconds`, TimeUnit.SECONDS)
    if (WebSettings.`gwen.web.maximize`) {
      driver.manage().window().maximize() 
    }
    driver
  }

  /**
    * Switches to the child window if one was just opened.
    *
    * @param driver the current web driver
    */
  private[web] def switchToChild(driver: WebDriver) {
    val children = driver.getWindowHandles.asScala.filter(window => !windows.contains(window)).toList match {
      case Nil if windows.size > 1 => windows.init
      case cs => cs
    }
    if (children.size == 1) {
      switchToWindow(children.head, isChild = true)
    } else if (children.size > 1) {
      ambiguousCaseError(s"Cannot determine which child window to switch to: ${children.size} were detected but only one is supported")
    } else {
      noSuchWindowError("Cannot switch to child window: no child window was found")
    }
  }

  /** Switches the driver to another window.
    *
    * @param handle the handle of the window to switch to
    * @param isChild true if switching to a child window; false for parent window
    */
  private def switchToWindow(handle: String, isChild: Boolean) {
    logger.info(s"Switching to ${if (isChild) "child" else "parent"} window ($handle)")
    drivers.get(session).fold(noSuchWindowError("Cannot switch to window: no windows currently open")) { _.switchTo.window(handle) }
    pushWindow(handle)
  }

  private[web] def closeChild() {
    if (windows.size > 1) {
      val child = windows.pop
      webDriver.switchTo.window(child)
      logger.info(s"Closing child window ($child)")
      webDriver.close()
      switchToParent(true)
    } else {
      noSuchWindowError("Cannot close child window: currently at root window which has no child")
    }
  }

  /** Switches to the parent window. */
  private[web] def switchToParent(childClosed: Boolean) {
    if (windows.nonEmpty) {
      val child = windows.pop
      val target = if (windows.nonEmpty) windows.top else child
      switchToWindow(target, isChild = false)
      if (!childClosed) { pushWindow(child) }
    } else {
      logger.warn("Bypassing switch to parent window: no child window currently open")
    }
  }

  private def pushWindow(window: String) {
    if (windows.isEmpty || windows.top != window) {
      windows push window
    }
  }

  /**
    * Switches the web driver session
    *
    * @param session the name of the session to switch to
    */
  def switchToSession(session: String) {
    logger.info(s"Switching to browser session: $session")
    this.session = session
    webDriver
  }
  
}